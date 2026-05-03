package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Extracts {@code streaming_service} (NF, AMZN, HULU, …).
 *
 * <p>Pattern catalogue is loaded from the {@code streaming_service} config
 * section, using the same flexible string/list/map shape as
 * {@link AudioCodecExtractor}.
 *
 * <p>Streaming-service tokens are short (often 2-4 letters) and would
 * generate huge numbers of false positives if accepted alone. {@link #postProcess}
 * keeps a candidate only if a {@code source} match (BluRay, WEB-DL, …) abuts
 * it within one character — releasers spell these adjacent ({@code "AMZN.WEB-DL"}),
 * and the source neighbour is what disambiguates the service token from a
 * coincidental short word.
 */
public final class StreamingServiceExtractor implements Extractor {

    public static final String STREAMING_SERVICE = "streaming_service";

    @Override public String name() { return STREAMING_SERVICE; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(STREAMING_SERVICE);
        if (section.isEmpty()) return;

        var input = ctx.input;
        for (var e : section.entrySet()) {
            String value = String.valueOf(e.getKey());
            for (var pat : flatten(e.getValue())) emit(ctx, input, value, pat);
        }
    }

    private static List<Object> flatten(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf(l);
        return List.of(v);
    }

    private static void emit(ParseContext ctx, String input, String value, Object pat) {
        if (pat instanceof String s) {
            if (s.startsWith("re:")) emitRegex(ctx, input, value, s.substring(3));
            else emitString(ctx, input, value, s, true);
        } else if (pat instanceof Map<?, ?> m) {
            var s = m.get("string");
            var r = m.get("regex");
            var p = m.get("pattern");
            boolean ignoreCase = !(m.get("ignore_case") instanceof Boolean b) || b;
            if (s instanceof String str) emitString(ctx, input, value, str, true);
            else if (s instanceof List<?> l) for (var x : l) emitString(ctx, input, value, x.toString(), true);
            if (p instanceof String str) emitString(ctx, input, value, str, ignoreCase);
            else if (p instanceof List<?> l) for (var x : l) emitString(ctx, input, value, x.toString(), ignoreCase);
            if (r instanceof String str) emitRegex(ctx, input, value, str);
            else if (r instanceof List<?> l) for (var x : l) emitRegex(ctx, input, value, x.toString());
        }
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle,
                                   boolean ignoreCase) {
        var hay = ignoreCase ? input.toLowerCase(Locale.ROOT) : input;
        var n = ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
        int from = 0;
        while (true) {
            int i = hay.indexOf(n, from);
            if (i < 0) break;
            int e = i + n.length();
            if (boundsOk(ctx, input, i, e)) {
                ctx.matches.add(new Match(STREAMING_SERVICE, value, i, e,
                    input.substring(i, e), 1000, Set.of("source-prefix"), false));
            }
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception _) { return; }
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            if (boundsOk(ctx, input, s, e)) {
                ctx.matches.add(new Match(STREAMING_SERVICE, value, s, e,
                    input.substring(s, e), 1000, Set.of("source-prefix"), false));
            }
        }
    }

    /** Accept the candidate when each side is either a sep/boundary or
     *  abuts an {@code other} match tagged {@code streaming_service.prefix}
     *  / {@code streaming_service.suffix} (e.g. {@code NetflixUHD},
     *  {@code AmazonHD}). Mirrors Python's StreamingServicePrefix /
     *  StreamingServiceSuffix proximity rules. */
    private static boolean boundsOk(ParseContext ctx, String input, int s, int e) {
        boolean leftOk = s == 0 || Seps.isSep(input.charAt(s - 1))
            || abutsStreamingTagged(ctx, s, true);
        boolean rightOk = e >= input.length() || Seps.isSep(input.charAt(e))
            || abutsStreamingTagged(ctx, e, false);
        return leftOk && rightOk;
    }

    private static boolean abutsStreamingTagged(ParseContext ctx, int pos, boolean prefixSide) {
        var tag = prefixSide ? "streaming_service.prefix" : "streaming_service.suffix";
        return ctx.matches.named("other")
            .filter(m -> m.tags().contains(tag))
            .anyMatch(m -> prefixSide ? m.end() == pos : m.start() == pos);
    }

    /** Replicates Python ValidateStreamingService — keep when there's a
     *  next match tagged {@code streaming_service.suffix} (or previous match
     *  tagged {@code streaming_service.prefix}) with only sep characters
     *  between the service and the neighbor. */
    @Override
    public void postProcess(ParseContext ctx) {
        var services = ctx.matches.named(STREAMING_SERVICE).toList();
        if (services.isEmpty()) return;
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        for (var s : services) {
            // next match with streaming_service.suffix tag, after the service
            var next = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.tags().contains("streaming_service.suffix"))
                .filter(m -> m.start() >= s.end())
                .min(Comparator.comparingInt(Match::start)).orElse(null);
            boolean nextOk = next != null
                && betweenIsSeps(input, s.end(), next.start())
                && (s.start() == 0 || Seps.isSep(input.charAt(s.start() - 1)));
            if (nextOk) continue;

            var prev = ctx.matches.all()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.tags().contains("streaming_service.prefix"))
                .filter(m -> m.end() <= s.start())
                .max(Comparator.comparingInt(Match::end)).orElse(null);
            boolean prevOk = prev != null
                && betweenIsSeps(input, prev.end(), s.start())
                && (s.end() >= input.length() || Seps.isSep(input.charAt(s.end())));
            if (prevOk) continue;

            toRemove.add(s);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean betweenIsSeps(String input, int from, int to) {
        if (from > to) return false;
        for (int i = from; i < to; i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }
}
