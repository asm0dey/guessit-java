package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the catch-all {@code other} property — the bag of release flags
 * (PROPER, REPACK, INTERNAL, REMUX, HDRip, etc.) and other miscellaneous
 * descriptors.
 *
 * <p>The full pattern catalogue is loaded from the {@code other} config
 * section, not hardcoded. Each entry can be:
 * <ul>
 *   <li>a single string (literal or {@code re:}-prefixed regex),</li>
 *   <li>a list of strings,</li>
 *   <li>a map with {@code string}/{@code regex}/{@code tags}/{@code validator}/
 *       {@code value}/{@code private_parent} keys.</li>
 * </ul>
 * {@link #emitSpec} flattens this shape into match emission calls. Tags drive
 * the post-process validators ({@code has-neighbor}, {@code at-end},
 * {@code other.validate.screener}, …) so most of the rules in this file are
 * tag-conditional clean-up passes.
 *
 * <p>{@link #emitCompleteWords} is a special case: matching the bare word
 * "Complete" produces too much noise, so it is only emitted when adjacent to
 * a season/series word (or article).
 */
public final class OtherExtractor implements Extractor {

    public static final String OTHER = "other";

    @Override public String name() { return OTHER; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(OTHER);
        var inner = section.get(OTHER);
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
        catch (Exception _) { return; }
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var match = createMatch(input, "Complete", Set.of(), s, e);
            if (validator.test(match)) ctx.matches.add(match);
        }
    }

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
        if (v instanceof List<?> l) return List.copyOf(l);
        return List.of(v);
    }

    private static void emitSpec(ParseContext ctx, String input, String key, Object spec) {
        if (spec instanceof String s) {
            if (key.startsWith("_")) return; // private with no value override skipped
            if (s.startsWith("re:")) emitRegex(ctx, input, key, s.substring(3), SENTINEL, defaultTags(), null, false);
            else emitString(ctx, input, key, s, SENTINEL, defaultTags());
            return;
        }
        if (!(spec instanceof Map<?, ?> m)) return;

        Object valueOverride = m.get("value");
        String otherValue = key.startsWith("_") ? null : key;
        String anotherValue = null;
        if (valueOverride instanceof Map<?, ?> vm) {
            if (vm.get(OTHER) != null) otherValue = vm.get(OTHER).toString();
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

        if (regexList instanceof String s) emitRegex(ctx, input, otherValue, s, validatorSrc, tags, anotherValue, privateParent);
        else if (regexList instanceof List<?> l) for (var p : l) emitRegex(ctx, input, otherValue, p.toString(), validatorSrc, tags, anotherValue, privateParent);
    }

    private static final Object SENTINEL = new Object();

    private static Set<String> defaultTags() { return Set.of(); }

    private static Set<String> parseTags(Object t) {
        switch (t) {
            case null -> {
                return Set.of();
            }
            case String s -> {
                return Set.of(s);
            }
            case List<?> l -> {
                var out = new HashSet<String>();
                for (var v : l) if (v != null) out.add(v.toString());
                return Set.copyOf(out);
            }
            default -> {
            }
        }
        return Set.of();
    }

    /**
     * Maps a config-side validator declaration to a {@link Predicate}.
     *
     * <p>The {@link #SENTINEL} object distinguishes "no key present in config"
     * (default to seps-surround) from "key explicitly null" (no validator at
     * all) — both round-trip through {@code Map.get} as null otherwise.
     */
    private static Predicate<Match> resolveValidator(String input, Object validatorSrc) {
        if (validatorSrc == SENTINEL) return Validators.sepsSurround(input);
        if (validatorSrc == null) return _ -> true; // explicit null → no validator
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
            var m = createMatch(input, value, tags, i, end);
            if (validator.test(m)) ctx.matches.add(m);
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src,
                                  Object validatorSrc, Set<String> tags, String anotherValue,
                                  boolean privateParent) {
        Pattern p;
        try {
            p = Pattern.compile(Abbreviations.dash(toJavaRegex(src)), Pattern.CASE_INSENSITIVE);
        } catch (Exception _) {
            return;
        }
        var validator = resolveValidator(input, validatorSrc);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            if (privateParent) {
                handlePrivateParentMatch(ctx, input, value, tags, anotherValue, validator, matcher);
            } else {
                handleStandardMatch(ctx, input, value, tags, anotherValue, validator, matcher);
            }
        }
    }

    private static void handlePrivateParentMatch(ParseContext ctx, String input, String value,
                                                 Set<String> tags, String anotherValue,
                                                 Predicate<Match> validator, Matcher matcher) {
        int s = matcher.start();
        int e = matcher.end();
        var parent = createMatch(input, value, tags, s, e);
        if (!validator.test(parent)) return;

        addGroupMatchIfValid(ctx, input, value, tags, matcher, s, e);
        addAnotherValueMatchIfPresent(ctx, input, anotherValue, tags, matcher);
    }

    private static void handleStandardMatch(ParseContext ctx, String input, String value,
                                            Set<String> tags, String anotherValue,
                                            Predicate<Match> validator, Matcher matcher) {
        int s = matcher.start();
        int e = matcher.end();
        var m = createMatch(input, value, tags, s, e);
        if (!validator.test(m)) return;

        ctx.matches.add(m);
        addAnotherValueMatchIfPresent(ctx, input, anotherValue, tags, matcher);
    }

    private static void addGroupMatchIfValid(ParseContext ctx, String input, String value,
                                             Set<String> tags, Matcher matcher,
                                             int defaultStart, int defaultEnd) {
        int groupS = matcher.groupCount() >= 1 ? matcher.start(1) : defaultStart;
        int groupE = matcher.groupCount() >= 1 ? matcher.end(1) : defaultEnd;
        if (groupS >= 0 && groupE > groupS) {
            ctx.matches.add(createMatch(input, value, tags, groupS, groupE));
        }
    }

    private static void addAnotherValueMatchIfPresent(ParseContext ctx, String input,
                                                      String anotherValue, Set<String> tags,
                                                      Matcher matcher) {
        if (anotherValue == null) return;

        int anotherS = groupStart(matcher);
        int anotherE = groupEnd(matcher);
        if (anotherS >= 0 && anotherE > anotherS) {
            ctx.matches.add(createMatch(input, anotherValue, tags, anotherS, anotherE));
        }
    }

    private static Match createMatch(String input, String value, Set<String> tags, int s, int e) {
        return new Match(OTHER, value, s, e, input.substring(s, e), 1000, tags, false);
    }

    private static final java.util.regex.Pattern PY_NAMED = java.util.regex.Pattern.compile("\\(\\?P<([^>]+)>");

    private static String toJavaRegex(String src) {
        // Convert Python-style named groups (?P<name>...) to Java (?<name>...).
        // Java disallows '_' in group names so strip non-alphanumerics from the
        // captured name (preserves the capture; the only metadata Java keeps is
        // the index, which downstream code does not consume here).
        var m = PY_NAMED.matcher(src);
        var sb = new StringBuilder();
        while (m.find()) {
            var safe = m.group(1).replaceAll("[^A-Za-z0-9]", "");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("(?<" + safe + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int groupStart(Matcher m) {
        try { return m.start("another"); } catch (IllegalArgumentException | IllegalStateException _) { return -1; }
    }
    private static int groupEnd(Matcher m) {
        try { return m.end("another"); } catch (IllegalArgumentException | IllegalStateException _) { return -1; }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        validateHasNeighbor(ctx);
        validateHasNeighborBefore(ctx);
        validateHasNeighborAfter(ctx);
        validateScreener(ctx);
        validateMux(ctx);
        validateStreamingServiceNeighbor(ctx);
        validateHardcodedSubs(ctx);
        validateAtEnd(ctx);
        dedupSameSpan(ctx);
    }

    /**
     * Drop {@code other=Hardcoded Subtitles} matches that aren't adjacent to a
     * {@code subtitle_language} match (only sep characters between). Mirrors
     * python's {@code ValidateHardcodedSubs}: bare "HC" without a subtitle
     * language neighbour is almost always part of a release tag (e.g.
     * {@code TEST.2015.1080p.HC.WEBRip} - HC there is a hardcoded label, but
     * python keeps "Hardcoded Subtitles" only when it's bound to a language).
     */
    private static void validateHardcodedSubs(ParseContext ctx) {
        var input = ctx.input;
        var subLangs = ctx.matches.named("subtitle_language").toList();
        var toRemove = new ArrayList<Match>();
        for (var hc : ctx.matches.named(OTHER)
            .filter(m -> "Hardcoded Subtitles".equals(m.value()))
            .toList()) {
            boolean keep = false;
            for (var sl : subLangs) {
                if (sl.start() >= hc.end() && Seps.betweenIsSeps(input, hc.end(), sl.start())) {
                    keep = true; break;
                }
                if (sl.end() <= hc.start() && Seps.betweenIsSeps(input, sl.end(), hc.start())) {
                    keep = true; break;
                }
            }
            if (!keep) toRemove.add(hc);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /**
     * Drop {@code other} matches tagged {@code streaming_service.{prefix,suffix}}
     * when they sit flush against a non-separator character on the wrong side.
     * Mirrors python guessit's {@code ValidateStreamingServiceNeighbor}: the tag
     * is meant for tokens that hug a streaming-service marker (e.g. {@code AmazonHD},
     * {@code NetflixUHD}); a bare {@code HD} stuck to a digit (e.g. {@code 2HD})
     * has no such neighbour and should not be kept.
     */
    private static void validateStreamingServiceNeighbor(ParseContext ctx) {
        var input = ctx.input;
        var toRemove = new ArrayList<Match>();
        var ssMatches = ctx.matches.named("streaming_service").toList();

        for (var m : ctx.matches.named(OTHER).toList()) {
            if (shouldRemoveStreamingServiceMatch(input, m, ssMatches)) {
                toRemove.add(m);
            }
        }

        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean shouldRemoveStreamingServiceMatch(String input, Match m, List<Match> ssMatches) {
        boolean hasPrefix = m.tags().contains("streaming_service.prefix");
        boolean hasSuffix = m.tags().contains("streaming_service.suffix");

        if (!hasPrefix && !hasSuffix) return false;

        boolean sepsAfter = m.end() >= input.length() || Seps.isSep(input.charAt(m.end()));
        boolean sepsBefore = m.start() == 0 || Seps.isSep(input.charAt(m.start() - 1));

        if (!sepsAfter && !isValidPrefixMatch(input, m, hasPrefix, ssMatches)) {
            return true;
        }

        return !sepsBefore && !isValidSuffixMatch(input, m, hasSuffix, ssMatches);
    }

    private static boolean isValidPrefixMatch(String input, Match m, boolean hasPrefix, List<Match> ssMatches) {
        if (!hasPrefix) return false;

        var next = ssMatches.stream()
                .filter(s -> s.start() >= m.end())
                .min(Comparator.comparingInt(Match::start))
                .orElse(null);

        return next != null && Seps.betweenIsSeps(input, m.end(), next.start());
    }

    private static boolean isValidSuffixMatch(String input, Match m, boolean hasSuffix, List<Match> ssMatches) {
        if (!hasSuffix) return false;

        var prev = ssMatches.stream()
                .filter(s -> s.end() <= m.start())
            .max(Comparator.comparingInt(Match::end))
            .orElse(null);
    
        return prev != null && Seps.betweenIsSeps(input, prev.end(), m.start());
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
        var others = ctx.matches.named(OTHER).filter(m -> m.tags().contains(tag)).toList();
        for (var m : others) {
            boolean ok = false;
            if (checkBefore) ok = hasAdjacentBefore(input, m, all, ctx.markers);
            if (checkAfter) ok = ok || hasAdjacentAfter(input, m, all, ctx.markers);
            if (!ok) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /**
     * "Adjacent" means the closest preceding non-private match (or group
     * marker close) is separated from {@code m} only by separator characters.
     * Group ends are considered alongside matches because release notes often
     * sit right after a closing bracket and should count as a neighbour.
     */
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



    private static void validateScreener(ParseContext ctx) {
        var input = ctx.input;
        var screeners = ctx.matches.named(OTHER)
            .filter(m -> m.tags().contains("other.validate.screener"))
            .toList();
        var sources = ctx.matches.named("source").toList();
        var toRemove = new ArrayList<Match>();
        for (var sc : screeners) {
            var src = sources.stream()
                .filter(s -> s.end() <= sc.start())
                .max(Comparator.comparingInt(Match::end))
                .orElse(null);
            if (src == null) { toRemove.add(sc); continue; }
            if (!Seps.betweenIsSeps(input, src.end(), sc.start())) toRemove.add(sc);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void validateMux(ParseContext ctx) {
        var muxes = ctx.matches.named(OTHER)
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
        var atEnds = ctx.matches.named(OTHER)
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
                    .anyMatch(x -> !x.name().equals(OTHER) && !x.name().equals("container"));
                if (nonOtherAfter) { toRemove.add(m); continue; }
                // Holes in [m.end, filepart.end] (gaps not covered by any non-private
                // match) must contain only separator chars. Mirrors python's
                // matches.holes(match.end, filepart.end, predicate=value.strip(seps)).
                if (hasNonSepHole(ctx, input, m.end(), filepart.end())) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean hasNonSepHole(ParseContext ctx, String input, int s, int e) {
        if (s >= e) return false;
        boolean[] covered = new boolean[e - s];
        ctx.matches.all()
            .filter(x -> !x.isPrivate())
            .filter(x -> x.start() < e && x.end() > s)
            .forEach(x -> {
                int from = Math.max(x.start(), s) - s;
                int to = Math.min(x.end(), e) - s;
                for (int i = from; i < to; i++) covered[i] = true;
            });
        for (int i = 0; i < covered.length; i++) {
            if (!covered[i] && !Seps.isSep(input.charAt(s + i))) return true;
        }
        return false;
    }


    private static void dedupSameSpan(ParseContext ctx) {
        // Drop duplicate "other" matches with same span and same value (can happen
        // across patterns). When duplicates carry distinct tag sets, keep the one
        // with the richer set so flag tags like source-prefix / source-suffix /
        // other.validate.screener survive to drive downstream validators.
        var groups = new java.util.LinkedHashMap<String, List<Match>>();
        for (var m : ctx.matches.named(OTHER).toList()) {
            var key = m.start() + ":" + m.end() + ":" + m.value();
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(m);
        }
        for (var grp : groups.values()) {
            if (grp.size() <= 1) continue;
            // Pick survivor: the largest tag set wins.
            var survivor = grp.stream()
                .max(java.util.Comparator.comparingInt(m -> m.tags().size()))
                .orElse(grp.getFirst());
            for (var m : grp) {
                if (m != survivor) ctx.matches.remove(m);
            }
        }
    }
}
