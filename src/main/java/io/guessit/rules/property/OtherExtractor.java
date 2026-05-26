package io.guessit.rules.property;

import io.guessit.engine.*;

import static io.guessit.rules.property.ConfigPatternHelpers.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    @Override
    public String name() { return OTHER; }

    @Override
    public String description() {
        return "other release flags (Proper, Repack, Internal, …)";
    }

    private record RegexDef(
            String value,
            String anotherValue,
            Set<String> tags,
            Object validatorSrc,
            boolean privateParent
    ) {}

    @Override
    public void extract(ParseContext ctx) {
        emitCompleteWords(ctx, forEachSpec(ctx, OTHER, OtherExtractor::emitSpec));
    }

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

        p.matcher(input).results()
                .map(res -> createMatch(MatchName.OTHER, input, "Complete", Set.of(), res.start(), res.end()))
                .filter(validator)
                .forEach(ctx.matches::add);
    }

    private static List<String> stringList(Object o, List<String> fallback) {
        if (o instanceof List<?> l) {
            return l.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return fallback;
    }

    private static void emitSpec(ParseContext ctx, String input, String key, Object spec) {
        if (spec instanceof String s) {
            if (key.startsWith("_")) return; // private with no value override skipped
            emitFromStringSpec(ctx, input, key, s);
            return;
        }
        if (spec instanceof Map<?, ?> m) emitFromMapSpec(ctx, input, key, m);
    }

    private static void emitFromStringSpec(ParseContext ctx, String input, String key, String s) {
        if (s.startsWith("re:")) {
            var def = new RegexDef(key, null, defaultTags(), SENTINEL, false);
            emitRegex(ctx, s.substring(3), def);
        } else {
            emitString(ctx, MatchName.OTHER, input, key, s, SENTINEL, defaultTags());
        }
    }

    private static void emitFromMapSpec(ParseContext ctx, String input, String key, Map<?, ?> m) {
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

        forEachString(m.get("string"),
                s -> emitString(ctx, MatchName.OTHER, input, finalOtherValue, s, validatorSrc, tags));

        var regexDef = new RegexDef(finalOtherValue, anotherValue, tags, validatorSrc, privateParent);
        forEachString(m.get("regex"), s -> emitRegex(ctx, s, regexDef));
    }

    private static void emitRegex(ParseContext ctx, String src, RegexDef def) {
        var p = compileDashedCi(toJavaRegex(src));
        if (p == null) return;

        var validator = resolveValidator(ctx.input, def.validatorSrc());
        var matcher = p.matcher(ctx.input);

        while (matcher.find()) {
            if (def.privateParent()) {
                handlePrivateParentMatch(ctx, def, validator, matcher);
            } else {
                handleStandardMatch(ctx, def, validator, matcher);
            }
        }
    }

    private static void handlePrivateParentMatch(ParseContext ctx, RegexDef def,
                                                 Predicate<Match> validator, Matcher matcher) {
        int s = matcher.start();
        int e = matcher.end();
        var parent = createMatch(MatchName.OTHER, ctx.input, def.value(), def.tags(), s, e);
        if (!validator.test(parent)) return;

        addGroupMatchIfValid(ctx, def, matcher, s, e);
        addAnotherValueMatchIfPresent(ctx, def, matcher);
    }

    private static void handleStandardMatch(ParseContext ctx, RegexDef def,
                                            Predicate<Match> validator, Matcher matcher) {
        int s = matcher.start();
        int e = matcher.end();
        var m = createMatch(MatchName.OTHER, ctx.input, def.value(), def.tags(), s, e);
        if (!validator.test(m)) return;

        ctx.matches.add(m);
        addAnotherValueMatchIfPresent(ctx, def, matcher);
    }

    private static void addGroupMatchIfValid(ParseContext ctx, RegexDef def, Matcher matcher,
                                             int defaultStart, int defaultEnd) {
        int groupS = matcher.groupCount() >= 1 ? matcher.start(1) : defaultStart;
        int groupE = matcher.groupCount() >= 1 ? matcher.end(1) : defaultEnd;
        if (groupS >= 0 && groupE > groupS) {
            ctx.matches.add(createMatch(MatchName.OTHER, ctx.input, def.value(), def.tags(), groupS, groupE));
        }
    }

    private static void addAnotherValueMatchIfPresent(ParseContext ctx, RegexDef def, Matcher matcher) {
        if (def.anotherValue() == null) return;

        int anotherS = groupStart(matcher);
        int anotherE = groupEnd(matcher);
        if (anotherS >= 0 && anotherE > anotherS) {
            ctx.matches.add(createMatch(MatchName.OTHER, ctx.input, def.anotherValue(), def.tags(), anotherS, anotherE));
        }
    }

    private static final Pattern PY_NAMED = Pattern.compile("\\(\\?P<([^>]+)>");

    private static String toJavaRegex(String src) {
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

    private static void validateHardcodedSubs(ParseContext ctx) {
        var input = ctx.input;
        var subtitlesLanguages = ctx.matches.named(MatchName.SUBTITLE_LANGUAGE).toList();

        ctx.matches.named(MatchName.OTHER)
                .filter(m -> "Hardcoded Subtitles".equals(m.value()))
                .filter(hc -> subtitlesLanguages.stream().noneMatch(sl -> isAdjacentSubtitle(input, hc, sl)))
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static boolean isAdjacentSubtitle(String input, Match hc, Match sl) {
        return (sl.start() >= hc.end() && Seps.betweenIsSeps(input, hc.end(), sl.start())) ||
                (sl.end() <= hc.start() && Seps.betweenIsSeps(input, sl.end(), hc.start()));
    }

    private static void validateStreamingServiceNeighbor(ParseContext ctx) {
        var ssMatches = ctx.matches.named(MatchName.STREAMING_SERVICE).toList();

        ctx.matches.named(MatchName.OTHER)
                .filter(m -> shouldRemoveStreamingServiceMatch(ctx.input, m, ssMatches))
                .toList()
                .forEach(ctx.matches::remove);
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
        var sources = ctx.matches.named(MatchName.SOURCE).toList();

        ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains("other.validate.screener"))
                .filter(sc -> sources.stream()
                        .filter(s -> s.end() <= sc.start())
                        .max(Comparator.comparingInt(Match::end))
                        .map(src -> !Seps.betweenIsSeps(input, src.end(), sc.start()))
                        .orElse(true))
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static void validateMux(ParseContext ctx) {
        var sources = ctx.matches.named(MatchName.SOURCE).toList();

        ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains("other.validate.mux"))
                .filter(mx -> sources.stream().noneMatch(s -> s.end() <= mx.start()))
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static void validateAtEnd(ParseContext ctx) {
        var pathMarkers = ctx.markers.stream()
                .filter(m -> "path".equals(m.name()))
                .toList();

        ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.tags().contains("at-end"))
                .filter(m -> pathMarkers.stream()
                        .filter(fp -> fp.covers(m.start(), m.end()))
                        .anyMatch(fp -> shouldRemoveAtEnd(ctx, ctx.input, fp, m)))
                .toList()
                .forEach(ctx.matches::remove);
    }

    private static boolean shouldRemoveAtEnd(ParseContext ctx, String input, Marker filePart, Match m) {
        boolean nonOtherAfter = ctx.matches.all()
                .filter(x -> !x.isPrivate())
                .filter(x -> x.start() >= m.end() && x.end() <= filePart.end())
                .anyMatch(x -> x.name() != MatchName.OTHER && x.name() != MatchName.CONTAINER);

        if (nonOtherAfter) return true;
        return hasNonSepHole(ctx, input, m.end(), filePart.end());
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
        var groups = ctx.matches.named(MatchName.OTHER)
                .collect(Collectors.groupingBy(
                        m -> m.start() + ":" + m.end() + ":" + m.value(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        groups.values().stream()
                .filter(grp -> grp.size() > 1)
                .forEach(grp -> {
                    var survivor = grp.stream()
                            .max(Comparator.comparingInt(m -> m.tags().size()))
                            .orElse(grp.getFirst());

                    grp.stream()
                            .filter(m -> m != survivor)
                            .forEach(ctx.matches::remove);
                });
    }
}