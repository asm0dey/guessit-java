package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.engine.MatchName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared building blocks for config-driven extractors that scan the input
 * with a flexible YAML/JSON-shaped pattern catalogue (currently
 * {@link OtherExtractor} and {@link EditionExtractor}).
 *
 * <p>All helpers are stateless and pure aside from the
 * {@linkplain #compileDashedCi pattern cache}.
 */
final class ConfigPatternHelpers {
    private ConfigPatternHelpers() {}

    /**
     * Marker distinguishing "config key absent" (default to seps-surround) from
     * "config key explicitly null" (no validator). Both round-trip through
     * {@code Map.get} as {@code null} otherwise.
     */
    static final Object SENTINEL = new Object();

    private static final ConcurrentMap<String, Pattern> DASHED_CACHE = new ConcurrentHashMap<>();

    static Pattern compileDashedCi(String src) {
        return DASHED_CACHE.computeIfAbsent(src, s -> {
            try { return Pattern.compile(Abbreviations.dash(s), Pattern.CASE_INSENSITIVE); }
            catch (PatternSyntaxException _) { return null; }
        });
    }

    static Set<String> defaultTags() { return Set.of(); }

    static List<Object> asList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf(l);
        return List.of(v);
    }

    /**
     * Invoke {@code action} for every string in {@code spec}, accepting either a
     * single string or a list of stringifiable values. {@code null}/other types
     * are ignored.
     */
    static void forEachString(Object spec, Consumer<String> action) {
        if (spec instanceof String s) action.accept(s);
        else if (spec instanceof List<?> l) for (var p : l) if (p != null) action.accept(p.toString());
    }

    /** Per-spec callback for {@link #forEachSpec}. */
    @FunctionalInterface
    interface SpecEmitter {
        void emit(ParseContext ctx, String input, String key, Object spec);
    }

    /**
     * Walk {@code ctx.config.section(sectionName).get(sectionName)} as the
     * standard "key → spec | [spec, spec, …]" map shape, invoking {@code emitter}
     * for every (key, spec) pair. Returns the raw entries map so callers can
     * pull out specially-keyed entries (e.g. {@code _complete_words}); returns
     * {@code null} when the section is missing or not a map.
     */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> forEachSpec(ParseContext ctx, String sectionName, SpecEmitter emitter) {
        var section = ctx.config.section(sectionName);
        var inner = section.get(sectionName);
        if (!(inner instanceof Map<?, ?> entries)) return null;
        var input = ctx.input;
        for (var e : entries.entrySet()) {
            var key = String.valueOf(e.getKey());
            for (var spec : asList(e.getValue())) {
                emitter.emit(ctx, input, key, spec);
            }
        }
        return (Map<Object, Object>) entries;
    }

    static Set<String> parseTags(Object t) {
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

    static Predicate<Match> resolveValidator(String input, Object validatorSrc) {
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

    static Match createMatch(MatchName name, String input, String value, Set<String> tags, int s, int e) {
        return new Match(name, value, s, e, input.substring(s, e), 1000, tags, false);
    }

    /**
     * Scan {@code input} for every (case-insensitive) occurrence of {@code needle},
     * adding a match per hit that passes {@code validatorSrc}.
     */
    static void emitString(ParseContext ctx, MatchName propName, String input, String value,
                           String needle, Object validatorSrc, Set<String> tags) {
        var validator = resolveValidator(input, validatorSrc);
        var hay = input.toLowerCase(Locale.ROOT);
        var n = needle.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = hay.indexOf(n, from);
            if (i < 0) break;
            int end = i + n.length();
            var match = createMatch(propName, input, value, tags, i, end);
            if (validator.test(match)) ctx.matches.add(match);
            from = i + 1;
        }
    }

    /**
     * "Adjacent" = closest preceding non-private match (or group-marker close)
     * is separated from {@code m} only by separator characters. Group ends are
     * considered alongside matches because release notes often sit right after
     * a closing bracket.
     */
    static boolean hasAdjacentBefore(String input, Match m, List<Match> all, List<Marker> markers) {
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

    static boolean hasAdjacentAfter(String input, Match m, List<Match> all, List<Marker> markers) {
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

    /**
     * Remove every {@code propName} match tagged with {@code tag} that lacks
     * the requested adjacency.
     */
    static void removeUnlessNeighbor(ParseContext ctx, MatchName propName, String tag,
                                     boolean checkBefore, boolean checkAfter) {
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        var all = ctx.matches.all().filter(m -> !m.isPrivate()).toList();
        var matches = ctx.matches.named(propName).filter(m -> m.tags().contains(tag)).toList();
        for (var m : matches) {
            boolean ok = false;
            if (checkBefore) ok = hasAdjacentBefore(input, m, all, ctx.markers);
            if (checkAfter) ok = ok || hasAdjacentAfter(input, m, all, ctx.markers);
            if (!ok) toRemove.add(m);
        }
        // Note: per-element remove is required — two distinct Match instances
        // can be equal by Match.equals (record), and bulk removeAll uses
        // contains-by-equals, which would incorrectly delete duplicates we
        // intend to keep.
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /** Drop later matches that share start, end, and value with an earlier one. */
    static void dedupSameSpan(ParseContext ctx, MatchName propName) {
        var seen = new HashSet<String>();
        var toRemove = new ArrayList<Match>();
        for (var m : ctx.matches.named(propName).toList()) {
            var key = m.start() + ":" + m.end() + ":" + m.value();
            if (!seen.add(key)) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
