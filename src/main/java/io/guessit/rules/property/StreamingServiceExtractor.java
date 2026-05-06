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
                ctx.matches.add(new Match(MatchName.STREAMING_SERVICE, value, i, e,
                    input.substring(i, e), 1000, Set.of("source-prefix"), false));
            }
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        var p = DASHED_CACHE.computeIfAbsent(src, s -> {
            try { return Pattern.compile(Abbreviations.dash(s), Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException ex) { ConfigPatternHelpers.warnBadRegex(s, ex); return null; }
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
            // Mirror python's matches.next/previous(predicate, 0) — find the
            // closest match position (any match, including private), then
            // require the predicate (streaming_service.suffix tag) on a match
            // at that position. Private matches participate in rebulk's
            // next/previous, so a private source like "VOD" embedded inside
            // "MBCVOD" qualifies as the suffix neighbor.
            // Note: walk from s.start()+1 (not s.end()) so a source span that
            // STARTS inside the streaming_service span (typical for python's
            // private_parent source on MBCVOD) is found.
            int sStart = s.start();
            int sEnd = s.end();
            int nextPos = ctx.matches.all()
                .filter(m -> m != s)
                .mapToInt(Match::start)
                .filter(p -> p > sStart)
                .min().orElse(-1);
            boolean nextOk = false;
            if (nextPos >= 0) {
                boolean atPosHasSuffix = ctx.matches.all()
                    .filter(m -> m != s)
                    .filter(m -> m.start() == nextPos)
                    .anyMatch(m -> m.tags().contains("streaming_service.suffix"));
                // Holes between service.end and next position must contain
                // only sep chars (or the next span starts inside service).
                boolean cleanGap = nextPos <= sEnd || betweenIsSeps(input, sEnd, nextPos);
                if (atPosHasSuffix && cleanGap
                        && (sStart == 0 || Seps.isSep(input.charAt(sStart - 1)))) {
                    nextOk = true;
                }
            }
            if (nextOk) continue;

            int prevPos = ctx.matches.all()
                .filter(m -> m != s)
                .mapToInt(Match::end)
                .filter(p -> p < sEnd)
                .max().orElse(-1);
            boolean prevOk = false;
            if (prevPos >= 0) {
                boolean atPosHasPrefix = ctx.matches.all()
                    .filter(m -> m != s)
                    .filter(m -> m.end() == prevPos)
                    .anyMatch(m -> m.tags().contains("streaming_service.prefix"));
                boolean cleanGap = prevPos >= sStart || betweenIsSeps(input, prevPos, sStart);
                if (atPosHasPrefix && cleanGap
                        && (sEnd >= input.length() || Seps.isSep(input.charAt(sEnd)))) {
                    prevOk = true;
                }
            }
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
