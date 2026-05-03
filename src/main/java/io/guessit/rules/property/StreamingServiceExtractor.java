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
            else emitString(ctx, input, value, s);
        } else if (pat instanceof Map<?, ?> m) {
            var s = m.get("string");
            var r = m.get("regex");
            if (s instanceof String str) emitString(ctx, input, value, str);
            else if (s instanceof List<?> l) for (var x : l) emitString(ctx, input, value, x.toString());
            if (r instanceof String str) emitRegex(ctx, input, value, str);
            else if (r instanceof List<?> l) for (var x : l) emitRegex(ctx, input, value, x.toString());
        }
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle) {
        var hay = input.toLowerCase(Locale.ROOT);
        var n = needle.toLowerCase(Locale.ROOT);
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

    /** Replicates Python ValidateStreamingService — service must abut a
     *  source-tagged neighbor, or an {@code other} match with the
     *  streaming_service.prefix/suffix tag (covers {@code NetflixUHD},
     *  {@code AmazonHD}, {@code iTunesHD}). */
    @Override
    public void postProcess(ParseContext ctx) {
        var services = ctx.matches.named(STREAMING_SERVICE).toList();
        if (services.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var s : services) {
            boolean hasSource = ctx.matches.named("source")
                .anyMatch(m -> Math.abs(m.start() - s.end()) <= 1 || Math.abs(s.start() - m.end()) <= 1);
            if (hasSource) continue;
            boolean abutsTagged =
                abutsStreamingTagged(ctx, s.start(), true)
                || abutsStreamingTagged(ctx, s.end(), false);
            if (!abutsTagged) toRemove.add(s);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
