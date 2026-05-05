package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Extracts the {@code edition} property — release edition tags such as
 * "Collector", "Director's Cut", "Extended", "Remastered", etc.
 *
 * <p>Patterns are loaded from the {@code edition.edition} config section
 * (under {@code advanced_config} in {@code options.json}). The config shape
 * mirrors the {@code other} section: each entry can be a plain string, a list
 * of specs, or a map with {@code string}/{@code regex}/{@code tags}/{@code value}
 * keys. Multi-value entries (keys starting with {@code _}) emit multiple
 * edition matches from one span.
 *
 * <p>Post-processing mirrors {@link OtherExtractor}'s {@code has-neighbor}
 * validation: matches tagged {@code has-neighbor} are dropped unless an
 * adjacent non-private match exists on either side (or the configured side).
 */
public final class EditionExtractor implements Extractor {

    public static final String EDITION = "edition";

    @Override
    public String name() { return EDITION; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(EDITION);
        var inner = section.get(EDITION);
        if (!(inner instanceof Map<?, ?> entries)) return;

        var input = ctx.input;
        for (var e : entries.entrySet()) {
            var key = String.valueOf(e.getKey());
            for (var spec : asList(e.getValue())) {
                emitSpec(ctx, input, key, spec);
            }
        }
    }

    // ── helpers mirroring OtherExtractor ──────────────────────────────────

    private static List<Object> asList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf(l);
        return List.of(v);
    }

    private static void emitSpec(ParseContext ctx, String input, String key, Object spec) {
        if (spec instanceof String s) {
            handleStringSpec(ctx, input, key, s);
            return;
        }
        if (!(spec instanceof Map<?, ?> m)) return;

        Object valueOverride = m.get("value");
        if (valueOverride instanceof List<?> multiValues) {
            handleMultiValueSpec(ctx, input, m, multiValues);
            return;
        }

        String editionValue = determineEditionValue(key, valueOverride);
        if (editionValue == null) return;

        var tags = parseTags(m.get("tags"));
        Object validatorSrc = m.containsKey("validator") ? m.get("validator") : SENTINEL;

        emitPatterns(ctx, input, editionValue, m.get("string"), m.get("regex"), validatorSrc, tags);
    }

    private static void handleStringSpec(ParseContext ctx, String input, String key, String s) {
        if (s.startsWith("re:")) {
            emitRegex(ctx, input, key, s.substring(3), SENTINEL, defaultTags());
        } else {
            emitString(ctx, input, key, s, SENTINEL, defaultTags());
        }
    }

    private static void handleMultiValueSpec(ParseContext ctx, String input, Map<?, ?> m, List<?> multiValues) {
        var tags = parseTags(m.get("tags"));
        Object regexList = m.get("regex");
        for (var val : multiValues) {
            var v = val.toString();
            emitRegexPatterns(ctx, input, v, regexList, SENTINEL, tags);
        }
    }

    private static String determineEditionValue(String key, Object valueOverride) {
        String editionValue = key.startsWith("_") ? null : key;
        if (valueOverride instanceof String s) editionValue = s;
        return editionValue;
    }

    private static void emitPatterns(ParseContext ctx, String input, String editionValue,
                                     Object stringList, Object regexList,
                                     Object validatorSrc, Set<String> tags) {
        emitStringPatterns(ctx, input, editionValue, stringList, validatorSrc, tags);
        emitRegexPatterns(ctx, input, editionValue, regexList, validatorSrc, tags);
    }

    private static void emitStringPatterns(ParseContext ctx, String input, String editionValue,
                                           Object stringList, Object validatorSrc, Set<String> tags) {
        if (stringList instanceof String s) {
            emitString(ctx, input, editionValue, s, validatorSrc, tags);
        } else if (stringList instanceof List<?> l) {
            for (var p : l) emitString(ctx, input, editionValue, p.toString(), validatorSrc, tags);
        }
    }

    private static void emitRegexPatterns(ParseContext ctx, String input, String editionValue,
                                          Object regexList, Object validatorSrc, Set<String> tags) {
        if (regexList instanceof String s) {
            emitRegex(ctx, input, editionValue, s, validatorSrc, tags);
        } else if (regexList instanceof List<?> l) {
            for (var p : l) emitRegex(ctx, input, editionValue, p.toString(), validatorSrc, tags);
        }
    }

    private static final Object SENTINEL = new Object();

    private static Set<String> defaultTags() { return Set.of(); }

    private static Set<String> parseTags(Object t) {
        return switch (t) {
            case String s -> Set.of(s);
            case List<?> l -> {
                var out = new HashSet<String>();
                for (var v : l) if (v != null) out.add(v.toString());
                yield Set.copyOf(out);
            }
            case null, default -> Set.of();
        };
    }

    private static Predicate<Match> resolveValidator(String input, Object validatorSrc) {
        if (validatorSrc == SENTINEL) return Validators.sepsSurround(input);
        if (validatorSrc == null) return _ -> true;
        if (validatorSrc instanceof String s) {
            return switch (s) {
                case "null" -> _ -> true;
                case "import:seps_after" -> Validators.sepsAfter(input);
                case "import:seps_before" -> Validators.sepsBefore(input);
                default -> Validators.sepsSurround(input);
            };
        }
        return Validators.sepsSurround(input);
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle,
                                    Object validatorSrc, Set<String> tags) {
        var validator = resolveValidator(input, validatorSrc);
        var hay = input.toLowerCase(java.util.Locale.ROOT);
        var n = needle.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (true) {
            int i = hay.indexOf(n, from);
            if (i < 0) break;
            int end = i + n.length();
            var match = createMatch(input, value, tags, i, end);
            if (validator.test(match)) ctx.matches.add(match);
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src,
                                   Object validatorSrc, Set<String> tags) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception _) { return; }
        var validator = resolveValidator(input, validatorSrc);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var m = createMatch(input, value, tags, s, e);
            if (!validator.test(m)) continue;
            ctx.matches.add(m);
        }
    }

    private static Match createMatch(String input, String value, Set<String> tags, int s, int e) {
        return new Match(EDITION, value, s, e, input.substring(s, e), 1000, tags, false);
    }

    // ── post-processing ───────────────────────────────────────────────────

    @Override
    public void postProcess(ParseContext ctx) {
        validateHasNeighbor(ctx);
        validateHasNeighborBefore(ctx);
        validateHasNeighborAfter(ctx);
        dropOverlappingStreamingService(ctx);
        dedupSameSpan(ctx);
    }

    private static void dropOverlappingStreamingService(ParseContext ctx) {
        var services = ctx.matches.named("streaming_service").toList();
        if (services.isEmpty()) return;
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        for (var ed : ctx.matches.named(EDITION).toList()) {
            for (var svc : services) {
                if (svc.start() != ed.start() || svc.end() != ed.end()) continue;
                if (!streamingServiceWillSurvive(ctx, input, svc)) continue;
                toRemove.add(ed);
                break;
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /**
     * Mirror of {@link StreamingServiceExtractor}'s post-pass: a streaming-service
     * match survives only when an adjacent source (suffix) or other (prefix)
     * neighbour with the right tag is separated by sep chars. We replicate the
     * check here because the edition pass runs before the streaming-service
     * pass — without it we would drop a CC-edition match in standalone "CC"
     * input where the streaming-service ends up dropped too.
     */
    private static boolean streamingServiceWillSurvive(ParseContext ctx, String input, Match s) {
        boolean nextOk = ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.tags().contains("streaming_service.suffix"))
            .filter(m -> m.start() >= s.end())
            .min(java.util.Comparator.comparingInt(Match::start))
            .map(n -> Seps.betweenIsSeps(input, s.end(), n.start())
                    && (s.start() == 0 || Seps.isSep(input.charAt(s.start() - 1))))
            .orElse(false);
        if (nextOk) return true;
        return ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.tags().contains("streaming_service.prefix"))
            .filter(m -> m.end() <= s.start())
            .max(java.util.Comparator.comparingInt(Match::end))
            .map(p -> Seps.betweenIsSeps(input, p.end(), s.start())
                    && (s.end() >= input.length() || Seps.isSep(input.charAt(s.end()))))
            .orElse(false);
    }

    private static void validateHasNeighbor(ParseContext ctx) {
        removeUnlessNeighbor(ctx, "has-neighbor", true, true);
    }

    private static void validateHasNeighborBefore(ParseContext ctx) {
        removeUnlessNeighbor(ctx, "has-neighbor-before", true, false);
    }

    private static void validateHasNeighborAfter(ParseContext ctx) {
        removeUnlessNeighbor(ctx, "has-neighbor-after", false, true);
    }

    private static void removeUnlessNeighbor(ParseContext ctx, String tag, boolean checkBefore, boolean checkAfter) {
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        var all = ctx.matches.all().filter(m -> !m.isPrivate()).toList();
        var editions = ctx.matches.named(EDITION).filter(m -> m.tags().contains(tag)).toList();
        for (var m : editions) {
            boolean ok = false;
            if (checkBefore) ok = hasAdjacentBefore(input, m, all, ctx.markers);
            if (checkAfter) ok = ok || hasAdjacentAfter(input, m, all, ctx.markers);
            if (!ok) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean hasAdjacentBefore(String input, Match m, List<Match> all, List<Marker> markers) {
        Match prev = null;
        for (var o : all) if (o != m && o.end() <= m.start() && (prev == null || o.end() > prev.end())) prev = o;
        Marker prevGroup = null;
        for (var g : markers) if ("group".equals(g.name()) && g.end() <= m.start() && (prevGroup == null || g.end() > prevGroup.end())) prevGroup = g;
        int prevEnd = -1;
        if (prev != null) prevEnd = prev.end();
        if (prevGroup != null && prevGroup.end() > prevEnd) prevEnd = prevGroup.end();
        if (prevEnd < 0) return false;
        return Seps.betweenIsSeps(input, prevEnd, m.start());
    }

    private static boolean hasAdjacentAfter(String input, Match m, List<Match> all, List<Marker> markers) {
        Match next = null;
        for (var o : all) if (o != m && o.start() >= m.end() && (next == null || o.start() < next.start())) next = o;
        Marker nextGroup = null;
        for (var g : markers) if ("group".equals(g.name()) && g.start() >= m.end() && (nextGroup == null || g.start() < nextGroup.start())) nextGroup = g;
        int nextStart = Integer.MAX_VALUE;
        if (next != null) nextStart = next.start();
        if (nextGroup != null && nextGroup.start() < nextStart) nextStart = nextGroup.start();
        if (nextStart == Integer.MAX_VALUE) return false;
        return Seps.betweenIsSeps(input, m.end(), nextStart);
    }


    private static void dedupSameSpan(ParseContext ctx) {
        var seen = new HashSet<String>();
        var toRemove = new ArrayList<Match>();
        for (var m : ctx.matches.named(EDITION).toList()) {
            var key = m.start() + ":" + m.end() + ":" + m.value();
            if (!seen.add(key)) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
