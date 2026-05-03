package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OtherExtractor implements Extractor {
    @Override public String name() { return "other"; }
    @Override public int priority() { return 1000; }

    @Override
    @SuppressWarnings("unchecked")
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("other");
        var inner = section.get("other");
        if (!(inner instanceof Map<?, ?> entries)) return;

        var input = ctx.input;
        for (var e : entries.entrySet()) {
            var key = String.valueOf(e.getKey());
            for (var spec : asList(e.getValue())) {
                emitSpec(ctx, input, key, spec);
            }
        }
        emitCompleteWords(ctx, entries);
    }

    /**
     * Mirrors Python's complete_words callable: matches "Complete" only when surrounded by
     * a season/series word (or article), e.g. "Season-Complete", "Complete-Series", "The-Complete-Series".
     * The configured tags ({@code release-group-prefix}) are not applied here since this match
     * already carries its own structural validation through the regex.
     */
    private static void emitCompleteWords(ParseContext ctx, Map<?, ?> entries) {
        var spec = entries.get("_complete_words");
        if (!(spec instanceof Map<?, ?> m)) return;
        var seasonWords = stringList(m.get("season_words"), List.of("seasons?", "series?"));
        var articleWords = stringList(m.get("complete_article_words"), List.of("The"));
        var seasonAlt = "(?:" + String.join("|", seasonWords) + ")";
        var articleAlt = "(?:" + String.join("|", articleWords) + ")";
        var src = "(?:" + articleAlt + "-)?(?:" + seasonAlt + "-)Complete"
                + "|(?:" + articleAlt + "-)?Complete(?:-" + seasonAlt + ")";
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignore) { return; }
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var match = new Match("other", "Complete", s, e, input.substring(s, e),
                1000, Set.of(), false);
            if (validator.test(match)) ctx.matches.add(match);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o, List<String> fallback) {
        if (o instanceof List<?> l) {
            var out = new java.util.ArrayList<String>(l.size());
            for (var v : l) if (v != null) out.add(v.toString());
            return out;
        }
        return fallback;
    }

    private static List<Object> asList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf((List<Object>) l);
        return List.of(v);
    }

    private static void emitSpec(ParseContext ctx, String input, String key, Object spec) {
        if (spec instanceof String s) {
            if (key.startsWith("_")) return; // private with no value override skipped
            if (s.startsWith("re:")) emitRegex(ctx, input, key, s.substring(3), SENTINEL, defaultTags(), null, false, false);
            else emitString(ctx, input, key, s, SENTINEL, defaultTags());
            return;
        }
        if (!(spec instanceof Map<?, ?> m)) return;

        Object valueOverride = m.get("value");
        String defaultValue = key.startsWith("_") ? null : key;
        String otherValue = defaultValue;
        String anotherValue = null;
        if (valueOverride instanceof Map<?, ?> vm) {
            if (vm.get("other") != null) otherValue = vm.get("other").toString();
            if (vm.get("another") != null) anotherValue = vm.get("another").toString();
        }
        if (otherValue == null) return;

        var tags = parseTags(m.get("tags"));
        Object validatorSrc = m.containsKey("validator") ? m.get("validator") : SENTINEL;
        boolean privateParent = Boolean.TRUE.equals(m.get("private_parent")) || Boolean.TRUE.equals(m.get("children"));

        Object stringList = m.get("string");
        Object regexList = m.get("regex");

        if (stringList instanceof String s) emitString(ctx, input, otherValue, s, validatorSrc, tags);
        else if (stringList instanceof List<?> l) for (var p : l) emitString(ctx, input, otherValue, p.toString(), validatorSrc, tags);

        if (regexList instanceof String s) emitRegex(ctx, input, otherValue, s, validatorSrc, tags, anotherValue, privateParent, false);
        else if (regexList instanceof List<?> l) for (var p : l) emitRegex(ctx, input, otherValue, p.toString(), validatorSrc, tags, anotherValue, privateParent, false);
    }

    private static final Object SENTINEL = new Object();

    private static Set<String> defaultTags() { return Set.of(); }

    @SuppressWarnings("unchecked")
    private static Set<String> parseTags(Object t) {
        if (t == null) return Set.of();
        if (t instanceof String s) return Set.of(s);
        if (t instanceof List<?> l) {
            var out = new HashSet<String>();
            for (var v : l) if (v != null) out.add(v.toString());
            return Set.copyOf(out);
        }
        return Set.of();
    }

    private static Predicate<Match> resolveValidator(String input, Object validatorSrc) {
        // Distinguish "key absent" (use default seps_surround) from "explicit null/None" (no validator).
        if (validatorSrc == SENTINEL) return Validators.sepsSurround(input);
        if (validatorSrc == null) return m -> true; // explicit null → no validator
        if (validatorSrc instanceof String s) {
            return switch (s) {
                case "null" -> m -> true;
                case "import:seps_after" -> Validators.sepsAfter(input);
                case "import:seps_before" -> Validators.sepsBefore(input);
                case "import:seps_surround" -> Validators.sepsSurround(input);
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
            var m = new Match("other", value, i, end, input.substring(i, end), 1000, tags, false);
            if (validator.test(m)) ctx.matches.add(m);
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src,
                                   Object validatorSrc, Set<String> tags, String anotherValue,
                                   boolean privateParent, boolean ignored) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(toJavaRegex(src)), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignore) { return; }
        var validator = resolveValidator(input, validatorSrc);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            int anotherS = -1, anotherE = -1;
            if (anotherValue != null) {
                anotherS = groupStart(matcher, "another");
                anotherE = groupEnd(matcher, "another");
            }
            // For private_parent (e.g., _HdRip), emit children only — no public parent match.
            if (privateParent) {
                int hdGroupS = matcher.groupCount() >= 1 ? matcher.start(1) : s;
                int hdGroupE = matcher.groupCount() >= 1 ? matcher.end(1) : e;
                var parent = new Match("other", value, s, e, input.substring(s, e), 1000, tags, false);
                if (!validator.test(parent)) continue;
                if (hdGroupS >= 0 && hdGroupE > hdGroupS) {
                    ctx.matches.add(new Match("other", value, hdGroupS, hdGroupE,
                        input.substring(hdGroupS, hdGroupE), 1000, tags, false));
                }
                if (anotherValue != null && anotherS >= 0 && anotherE > anotherS) {
                    ctx.matches.add(new Match("other", anotherValue, anotherS, anotherE,
                        input.substring(anotherS, anotherE), 1000, tags, false));
                }
                continue;
            }
            var m = new Match("other", value, s, e, input.substring(s, e), 1000, tags, false);
            if (!validator.test(m)) continue;
            ctx.matches.add(m);
            if (anotherValue != null && anotherS >= 0 && anotherE > anotherS) {
                ctx.matches.add(new Match("other", anotherValue, anotherS, anotherE,
                    input.substring(anotherS, anotherE), 1000, tags, false));
            }
        }
    }

    private static String toJavaRegex(String src) {
        // Convert Python-style named groups (?P<name>...) to Java (?<name>...)
        return src.replace("(?P<", "(?<");
    }

    private static int groupStart(Matcher m, String name) {
        try { return m.start(name); } catch (IllegalArgumentException | IllegalStateException e) { return -1; }
    }
    private static int groupEnd(Matcher m, String name) {
        try { return m.end(name); } catch (IllegalArgumentException | IllegalStateException e) { return -1; }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        validateHasNeighbor(ctx);
        validateHasNeighborBefore(ctx);
        validateHasNeighborAfter(ctx);
        validateScreener(ctx);
        validateMux(ctx);
        validateAtEnd(ctx);
        renameAnother(ctx);
        dedupSameSpan(ctx);
    }

    private static void validateHasNeighbor(ParseContext ctx) {
        removeUnlessNeighbor(ctx, "has-neighbor", true, true);
    }
    private static void validateHasNeighborBefore(ParseContext ctx) {
        // Per Python docstring: previous match must exist (adjacent).
        removeUnlessNeighbor(ctx, "has-neighbor-before", true, false);
    }
    private static void validateHasNeighborAfter(ParseContext ctx) {
        removeUnlessNeighbor(ctx, "has-neighbor-after", false, true);
    }

    private static void removeUnlessNeighbor(ParseContext ctx, String tag, boolean checkBefore, boolean checkAfter) {
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        var all = ctx.matches.all().filter(m -> !m.isPrivate()).toList();
        var others = ctx.matches.named("other").filter(m -> m.tags().contains(tag)).toList();
        for (var m : others) {
            boolean ok = false;
            if (checkBefore) ok = ok || hasAdjacentBefore(input, m, all, ctx.markers);
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
        return between(input, prevEnd, m.start());
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
        return between(input, m.end(), nextStart);
    }

    private static boolean between(String input, int s, int e) {
        if (s >= e) return true;
        for (int i = s; i < e; i++) if (!Seps.isSep(input.charAt(i))) return false;
        return true;
    }

    private static void validateScreener(ParseContext ctx) {
        var input = ctx.input;
        var screeners = ctx.matches.named("other")
            .filter(m -> m.tags().contains("other.validate.screener"))
            .toList();
        var sources = ctx.matches.named("source").toList();
        var toRemove = new ArrayList<Match>();
        for (var sc : screeners) {
            var src = sources.stream()
                .filter(s -> s.end() <= sc.start())
                .max((a, b) -> Integer.compare(a.end(), b.end()))
                .orElse(null);
            if (src == null) { toRemove.add(sc); continue; }
            if (!between(input, src.end(), sc.start())) toRemove.add(sc);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void validateMux(ParseContext ctx) {
        var muxes = ctx.matches.named("other")
            .filter(m -> m.tags().contains("other.validate.mux"))
            .toList();
        var sources = ctx.matches.named("source").toList();
        var toRemove = new ArrayList<Match>();
        for (var mx : muxes) {
            boolean hasPrevSource = sources.stream().anyMatch(s -> s.end() <= mx.start());
            if (!hasPrevSource) toRemove.add(mx);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void validateAtEnd(ParseContext ctx) {
        var input = ctx.input;
        var atEnds = ctx.matches.named("other")
            .filter(m -> m.tags().contains("at-end"))
            .toList();
        var toRemove = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var m : atEnds) {
                if (!filepart.covers(m.start(), m.end())) continue;
                // Anything non-other/non-container after the match → remove
                boolean nonOtherAfter = ctx.matches.all()
                    .filter(x -> !x.isPrivate())
                    .filter(x -> x.start() >= m.end() && x.end() <= filepart.end())
                    .anyMatch(x -> !x.name().equals("other") && !x.name().equals("container"));
                if (nonOtherAfter) { toRemove.add(m); continue; }
                // Hole filled with non-sep content after match → remove
                if (!between(input, m.end(), filepart.end())) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void renameAnother(ParseContext ctx) {
        // Currently we already emit `another` capture as `other` directly; nothing to rename.
    }

    private static void dedupSameSpan(ParseContext ctx) {
        // Drop duplicate "other" matches with same span and same value (can happen across patterns).
        var seen = new HashSet<String>();
        var toRemove = new ArrayList<Match>();
        for (var m : ctx.matches.named("other").toList()) {
            var key = m.start() + ":" + m.end() + ":" + m.value();
            if (!seen.add(key)) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
