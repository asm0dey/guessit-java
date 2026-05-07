package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Locale;

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

    private static final ConcurrentMap<String, Pattern> DASHED_CACHE = new ConcurrentHashMap<>();

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
            emitStringOrRegex(ctx, input, value, s);
        } else if (pat instanceof Map<?, ?> m) {
            emitFromMap(ctx, input, value, m);
        }
    }

    private static void emitStringOrRegex(ParseContext ctx, String input, String value, String s) {
        if (s.startsWith("re:")) emitRegex(ctx, input, value, s.substring(3));
        else emitString(ctx, input, value, s, true);
    }

    private static void emitFromMap(ParseContext ctx, String input, String value, Map<?, ?> m) {
        boolean ignoreCase = !(m.get("ignore_case") instanceof Boolean b) || b;
        emitStringOrList(ctx, input, value, m.get("string"), true);
        emitStringOrList(ctx, input, value, m.get("pattern"), ignoreCase);
        emitRegexOrList(ctx, input, value, m.get("regex"));
    }

    private static void emitStringOrList(ParseContext ctx, String input, String value, Object v, boolean ignoreCase) {
        if (v instanceof String str) emitString(ctx, input, value, str, ignoreCase);
        else if (v instanceof List<?> l) for (var x : l) emitString(ctx, input, value, x.toString(), ignoreCase);
    }

    private static void emitRegexOrList(ParseContext ctx, String input, String value, Object v) {
        if (v instanceof String str) emitRegex(ctx, input, value, str);
        else if (v instanceof List<?> l) for (var x : l) emitRegex(ctx, input, value, x.toString());
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
                ctx.matches.add(new Match(MatchName.STREAMING_SERVICE, value, i, e,
                    input.substring(i, e), 1000, Set.of("source-prefix"), false));
            }
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        var p = DASHED_CACHE.computeIfAbsent(src, s -> {
            try { return Pattern.compile(Abbreviations.dash(s), Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException _) { return null; }
        });
        if (p == null) return;
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            if (boundsOk(ctx, input, s, e)) {
                ctx.matches.add(new Match(MatchName.STREAMING_SERVICE, value, s, e,
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
        return ctx.matches.named(MatchName.OTHER)
            .filter(m -> m.tags().contains(tag))
            .anyMatch(m -> prefixSide ? m.end() == pos : m.start() == pos);
    }

    /** Replicates Python ValidateStreamingService — keep when there's a
     *  next match tagged {@code streaming_service.suffix} (or previous match
     *  tagged {@code streaming_service.prefix}) with only sep characters
     *  between the service and the neighbor. */
    @Override
    public void postProcess(ParseContext ctx) {
        var services = ctx.matches.named(MatchName.STREAMING_SERVICE).toList();
        if (services.isEmpty()) return;
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        for (var s : services) {
            if (hasSuffixNeighbor(ctx, input, s)) continue;
            if (hasPrefixNeighbor(ctx, input, s)) continue;
            toRemove.add(s);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /** True when a match at the closest next position carries
     *  {@code streaming_service.suffix} and the gap is sep-only. Private
     *  matches participate (e.g. private source "VOD" inside "MBCVOD"). */
    private static boolean hasSuffixNeighbor(ParseContext ctx, String input, Match s) {
        int sStart = s.start();
        int sEnd = s.end();
        int nextPos = ctx.matches.all()
            .filter(m -> m != s)
            .mapToInt(Match::start)
            .filter(p -> p > sStart)
            .min().orElse(-1);
        if (nextPos < 0) return false;
        boolean atPosHasSuffix = ctx.matches.all()
            .filter(m -> m != s)
            .filter(m -> m.start() == nextPos)
            .anyMatch(m -> m.tags().contains("streaming_service.suffix"));
        boolean cleanGap = nextPos <= sEnd || betweenIsSeps(input, sEnd, nextPos);
        return atPosHasSuffix && cleanGap
                && (sStart == 0 || Seps.isSep(input.charAt(sStart - 1)));
    }

    private static boolean hasPrefixNeighbor(ParseContext ctx, String input, Match s) {
        int sStart = s.start();
        int sEnd = s.end();
        int prevPos = ctx.matches.all()
            .filter(m -> m != s)
            .mapToInt(Match::end)
            .filter(p -> p < sEnd)
            .max().orElse(-1);
        if (prevPos < 0) return false;
        boolean atPosHasPrefix = ctx.matches.all()
            .filter(m -> m != s)
            .filter(m -> m.end() == prevPos)
            .anyMatch(m -> m.tags().contains("streaming_service.prefix"));
        boolean cleanGap = prevPos >= sStart || betweenIsSeps(input, prevPos, sStart);
        return atPosHasPrefix && cleanGap
                && (sEnd >= input.length() || Seps.isSep(input.charAt(sEnd)));
    }

    private static boolean betweenIsSeps(String input, int from, int to) {
        if (from > to) return false;
        for (int i = from; i < to; i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }
}
