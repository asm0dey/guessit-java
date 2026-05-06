package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.engine.MatchName;

import static io.guessit.rules.property.ConfigPatternHelpers.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String ANOTHER_KEY = "another";

    @Override public String name() { return OTHER; }

    @Override
    public void extract(ParseContext ctx) {
        var entries = forEachSpec(ctx, OTHER, OtherExtractor::emitSpec);
        if (entries != null) emitCompleteWords(ctx, entries);
    }

    /**
     * Mirrors Python's complete_words callable: matches "Complete" only when surrounded by
     * a season/series word (or article), e.g. "Season-Complete", "Complete-Series", "The-Complete-Series".
     * The configured tags ({@code release-group-prefix}) are not applied here since this match
     * already carries its own structural validation through the regex.
     */
    private static void emitCompleteWords(ParseContext ctx, Map<Object, Object> entries) {
        var spec = entries.get("_complete_words");
        if (!(spec instanceof Map<?, ?> m)) return;
        var seasonWords = stringList(m.get("season_words"), List.of("seasons?", "series?"));
        var articleWords = stringList(m.get("complete_article_words"), List.of("The"));
        var seasonAlt = "(?:" + String.join("|", seasonWords) + ")";
        var articleAlt = "(?:" + String.join("|", articleWords) + ")";
        var src = "(?:" + articleAlt + "-)?(?:" + seasonAlt + "-)Complete"
                + "|(?:" + articleAlt + "-)?Complete(?:-" + seasonAlt + ")";
        var p = compileDashedCi(src);
        if (p == null) return;
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var match = createMatch(MatchName.OTHER, input, "Complete", Set.of(), s, e);
            if (validator.test(match)) ctx.matches.add(match);
        }
    }

    private static List<String> stringList(Object o, List<String> fallback) {
        if (o instanceof List<?> l) {
            var out = new ArrayList<String>(l.size());
            for (var v : l) if (v != null) out.add(v.toString());
            return out;
        }
        return fallback;
    }

    private static void emitSpec(ParseContext ctx, String input, String key, Object spec) {
        if (spec instanceof String s) {
            if (key.startsWith("_")) return; // private with no value override skipped
            if (s.startsWith("re:")) emitRegex(ctx, input, key, s.substring(3), SENTINEL, defaultTags(), null, false);
            else emitString(ctx, MatchName.OTHER, input, key, s, SENTINEL, defaultTags());
            return;
        }
        if (!(spec instanceof Map<?, ?> m)) return;

        Object valueOverride = m.get("value");
        String otherValue = key.startsWith("_") ? null : key;
        String anotherValue = null;
        if (valueOverride instanceof Map<?, ?> vm) {
            if (vm.get(OTHER) != null) otherValue = vm.get(OTHER).toString();
            if (vm.get(ANOTHER_KEY) != null) anotherValue = vm.get(ANOTHER_KEY).toString();
        }
        if (otherValue == null) return;

        var tags = parseTags(m.get("tags"));
        Object validatorSrc = m.containsKey("validator") ? m.get("validator") : SENTINEL;
        boolean privateParent = Boolean.TRUE.equals(m.get("private_parent")) || Boolean.TRUE.equals(m.get("children"));

        var finalOtherValue = otherValue;
        var finalAnother = anotherValue;
        forEachString(m.get("string"),
            s -> emitString(ctx, MatchName.OTHER, input, finalOtherValue, s, validatorSrc, tags));
        forEachString(m.get("regex"),
            s -> emitRegex(ctx, input, finalOtherValue, s, validatorSrc, tags, finalAnother, privateParent));
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src,
                                  Object validatorSrc, Set<String> tags, String anotherValue,
                                  boolean privateParent) {
        var p = compileDashedCi(toJavaRegex(src));
        if (p == null) return;
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
        var parent = createMatch(MatchName.OTHER, input, value, tags, s, e);
        if (!validator.test(parent)) return;

        addGroupMatchIfValid(ctx, input, value, tags, matcher, s, e);
        addAnotherValueMatchIfPresent(ctx, input, anotherValue, tags, matcher);
    }

    private static void handleStandardMatch(ParseContext ctx, String input, String value,
                                            Set<String> tags, String anotherValue,
                                            Predicate<Match> validator, Matcher matcher) {
        int s = matcher.start();
        int e = matcher.end();
        var m = createMatch(MatchName.OTHER, input, value, tags, s, e);
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
            ctx.matches.add(createMatch(MatchName.OTHER, input, value, tags, groupS, groupE));
        }
    }

    private static void addAnotherValueMatchIfPresent(ParseContext ctx, String input,
                                                      String anotherValue, Set<String> tags,
                                                      Matcher matcher) {
        if (anotherValue == null) return;

        int anotherS = groupStart(matcher);
        int anotherE = groupEnd(matcher);
        if (anotherS >= 0 && anotherE > anotherS) {
            ctx.matches.add(createMatch(MatchName.OTHER, input, anotherValue, tags, anotherS, anotherE));
        }
    }

    private static final Pattern PY_NAMED = Pattern.compile("\\(\\?P<([^>]+)>");

    private static String toJavaRegex(String src) {
        // Convert Python-style named groups (?P<name>...) to Java (?<name>...).
        // Java disallows '_' in group names so strip non-alphanumerics from the
        // captured name (preserves the capture; the only metadata Java keeps is
        // the index, which downstream code does not consume here).
        var m = PY_NAMED.matcher(src);
        var sb = new StringBuilder();
        while (m.find()) {
            var safe = m.group(1).replaceAll("[^A-Za-z0-9]", "");
            m.appendReplacement(sb, Matcher.quoteReplacement("(?<" + safe + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int groupStart(Matcher m) {
        try { return m.start(ANOTHER_KEY); } catch (IllegalArgumentException | IllegalStateException _) { return -1; }
    }
    private static int groupEnd(Matcher m) {
        try { return m.end(ANOTHER_KEY); } catch (IllegalArgumentException | IllegalStateException _) { return -1; }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        removeUnlessNeighbor(ctx, MatchName.OTHER, "has-neighbor", true, true);
        removeUnlessNeighbor(ctx, MatchName.OTHER, "has-neighbor-before", true, false);
        removeUnlessNeighbor(ctx, MatchName.OTHER, "has-neighbor-after", false, true);
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
        var subLangs = ctx.matches.named(MatchName.SUBTITLE_LANGUAGE).toList();
        var toRemove = new ArrayList<Match>();
        for (var hc : ctx.matches.named(MatchName.OTHER)
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
        var ssMatches = ctx.matches.named(MatchName.STREAMING_SERVICE).toList();

        for (var m : ctx.matches.named(MatchName.OTHER).toList()) {
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

    private static void validateScreener(ParseContext ctx) {
        var input = ctx.input;
        var screeners = ctx.matches.named(MatchName.OTHER)
            .filter(m -> m.tags().contains("other.validate.screener"))
            .toList();
        var sources = ctx.matches.named(MatchName.SOURCE).toList();
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
        var muxes = ctx.matches.named(MatchName.OTHER)
            .filter(m -> m.tags().contains("other.validate.mux"))
            .toList();
        var sources = ctx.matches.named(MatchName.SOURCE).toList();
        var toRemove = new ArrayList<Match>();
        for (var mx : muxes) {
            boolean hasPrevSource = sources.stream().anyMatch(s -> s.end() <= mx.start());
            if (!hasPrevSource) toRemove.add(mx);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void validateAtEnd(ParseContext ctx) {
        var input = ctx.input;
        var atEnds = ctx.matches.named(MatchName.OTHER)
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
                    .anyMatch(x -> x.name() != MatchName.OTHER && x.name() != MatchName.CONTAINER);
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

    /**
     * Drop duplicate "other" matches with same span and same value (can happen
     * across patterns). When duplicates carry distinct tag sets, keep the one
     * with the richer set so flag tags like source-prefix / source-suffix /
     * other.validate.screener survive to drive downstream validators.
     */
    private static void dedupSameSpan(ParseContext ctx) {
        var groups = new LinkedHashMap<String, List<Match>>();
        for (var m : ctx.matches.named(MatchName.OTHER).toList()) {
            var key = m.start() + ":" + m.end() + ":" + m.value();
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(m);
        }
        var toRemove = new ArrayList<Match>();
        for (var grp : groups.values()) {
            if (grp.size() <= 1) continue;
            var survivor = grp.stream()
                .max(Comparator.comparingInt(m -> m.tags().size()))
                .orElse(grp.getFirst());
            for (var m : grp) {
                if (m != survivor) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
