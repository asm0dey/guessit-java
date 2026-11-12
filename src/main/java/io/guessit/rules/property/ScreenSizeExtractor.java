package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.SiftPatterns;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

public final class ScreenSizeExtractor implements Extractor {

    public static final String SCREEN_SIZE = "screen_size";
    public static final String WEAK_SCREEN_SIZE = "weak.screen_size";
    public static final String NORMALIZED = "normalized";

    private static final String GRP_HEIGHT = "height";
    private static final String GRP_WIDTH = "width";
    private static final String GRP_SCAN = "scan";

    private static final Pattern WH_P = buildWhPattern();
    private static final Pattern WIDTH_HEIGHT_NORM = buildWidthHeightNorm();
    private static final Pattern HEIGHT_SCAN_NORM = buildHeightScanNorm();

    private static final String GRP_FRAME_RATE = "fr";
    private static final Pattern FRAME_RATE_PATTERN = buildFrameRatePattern();
    private static final String GRP_VALUE = "value";

    private final ConcurrentMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private static Pattern buildWhPattern() {
        var spaces = zeroOrMore().whitespace();

        var pattern = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .namedCapture(capture(GRP_WIDTH, between(3, 4).digits()))
                .then().optional().character('-')
                .then().of(spaces)
                .then().exactly(1).of(anyOf(literal("x"), literal("*")))
                .then().of(spaces)
                .then().optional().character('-')
                .then().namedCapture(capture(GRP_HEIGHT, between(3, 4).digits()));

        return Pattern.compile(pattern.shake());
    }

    private static Pattern buildWidthHeightNorm() {
        var spaces = zeroOrMore().whitespace();

        var pattern = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .namedCapture(capture(GRP_WIDTH, between(3, 4).digits()))
                .then().of(spaces)
                .then().exactly(1).of(anyOf(literal("x"), literal("*"), literal("-")))
                .then().of(spaces)
                .then().namedCapture(capture(GRP_HEIGHT, between(3, 4).digits()))
                .then().optional().of(fromAnywhere().namedCapture(capture(GRP_SCAN, anyOf(literal("i"), literal("p")))));

        return Pattern.compile(pattern.shake());
    }

    private static Pattern buildHeightScanNorm() {
        var pattern = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .namedCapture(capture(GRP_HEIGHT, between(3, 4).digits()))
                .then().optional().of(fromAnywhere().namedCapture(capture(GRP_SCAN, anyOf(literal("i"), literal("p")))));

        return Pattern.compile(pattern.shake());
    }

    private static Pattern buildFrameRatePattern() {
        var frDecimals = exactly(1).character('.').then().between(1, 3).digits();
        var frNum = exactly(2).digits().followedBy(optional().of(frDecimals));

        var pattern = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .between(3, 4).digits()
                .then().exactly(1).of(anyOf(literal("i"), literal("p")))
                .then().of(fromAnywhere().namedCapture(capture(GRP_FRAME_RATE, frNum)))
                .andNothingElse();

        return Pattern.compile(pattern.shake());
    }

    @Override
    public String name() {
        return SCREEN_SIZE;
    }

    @Override
    public String description() {
        return "resolution (480p / 720p / 1080p / 2160p / 4K, including i variants)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(SCREEN_SIZE);
        var validator = Validators.sepsSurround(ctx.input);
        var opts = RegexOpts.defaults().withValidator(validator);

        extractFallbackWidthHeight(ctx, opts);
        extractDynamicPatterns(ctx, section, opts, validator);
        extract4kLiteral(ctx, validator);
        extractStandaloneFrameRate(ctx, section, validator);
    }

    private void extractFallbackWidthHeight(ParseContext ctx, RegexOpts opts) {
        for (var m : PatternMatcher.regex(ctx.input, WH_P, MatchName.SCREEN_SIZE, opts, ctx.trace)) {
            ctx.matches.add(m);
        }
    }

    private void extractDynamicPatterns(ParseContext ctx, Map<String, Object> section, RegexOpts opts, java.util.function.Predicate<Match> validator) {
        var interlaced = stringList(section.get("interlaced"));
        var progressive = stringList(section.get("progressive"));
        var frameRates = stringList(section.get("frame_rates"));

        var siftResPrefixInner = fromAnywhere().namedCapture(capture(GRP_WIDTH, between(3, 4).digits()))
                .followedBy(anyOf(literal("x"), literal("*")));
        var siftResPrefix = optional().of(siftResPrefixInner);

        if (!interlaced.isEmpty()) {
            var basePatternI = exactly(1).of(siftResPrefix)
                    .then().namedCapture(capture(GRP_HEIGHT, anyOfList(interlaced)))
                    .then().namedCapture(capture(GRP_SCAN, literal("i")));

            var finalPatternI = frameRates.isEmpty()
                    ? basePatternI
                    : basePatternI.then().optional().of(anyOfFrameRates(frameRates));

            addSiftPattern(ctx, finalPatternI, opts, MatchName.SCREEN_SIZE);
        }

        if (!progressive.isEmpty()) {
            var basePatternP = exactly(1).of(siftResPrefix)
                    .then().namedCapture(capture(GRP_HEIGHT, anyOfList(progressive)))
                    .then().namedCapture(capture(GRP_SCAN, literal("p")));

            var finalPatternP = frameRates.isEmpty()
                    ? basePatternP
                    : basePatternP.then().optional().of(anyOfFrameRates(frameRates));

            addSiftPattern(ctx, finalPatternP, opts, MatchName.SCREEN_SIZE);

            var siftHeightP = fromAnywhere().namedCapture(capture(GRP_HEIGHT, anyOfList(progressive)));
            var optScanP = optional().of(fromAnywhere().namedCapture(capture(GRP_SCAN, literal("p"))));

            var patternPHd = exactly(1).of(siftResPrefix)
                    .then().of(siftHeightP)
                    .then().of(optScanP)
                    .followedBy(literal("hd"));
            addSiftPattern(ctx, patternPHd, opts, MatchName.SCREEN_SIZE);

            var patternPX = exactly(1).of(siftResPrefix)
                    .then().of(siftHeightP)
                    .then().of(optScanP)
                    .followedBy(literal("x"));
            addSiftPattern(ctx, patternPX, opts, MatchName.SCREEN_SIZE);

            var weakOpts = RegexOpts.defaults()
                    .withValidator(validator)
                    .withTags(Set.of(WEAK_SCREEN_SIZE));
            var patternPWeak = exactly(1).of(siftResPrefix)
                    .then().of(siftHeightP);
            addSiftPattern(ctx, patternPWeak, weakOpts, MatchName.SCREEN_SIZE);
        }
    }

    private void extract4kLiteral(ParseContext ctx, java.util.function.Predicate<Match> validator) {
        var fourK = StringOpts.defaults().withValidator(validator);
        for (var m : PatternMatcher.string(ctx.input, Set.of("4k"), MatchName.SCREEN_SIZE, fourK, ctx.trace)) {
            ctx.matches.add(new Match(MatchName.SCREEN_SIZE, "2160p", m.start(), m.end(), m.raw(),
                    m.priority(), Set.of(NORMALIZED), false));
        }
    }

    private void extractStandaloneFrameRate(ParseContext ctx, Map<String, Object> section, java.util.function.Predicate<Match> validator) {
        var frameRates = stringList(section.get("frame_rates"));
        if (frameRates.isEmpty()) return;

        var frOpts = RegexOpts.defaults()
                .withValue(s -> {
                    int dotIdx = s.indexOf('.');
                    return Integer.valueOf(dotIdx == -1 ? s : s.substring(0, dotIdx));
                })
                .withTags(Set.of("coexist"))
                .withValidator(validator);

        var frStandalone = fromAnywhere().namedCapture(capture(GRP_VALUE, anyOfFrameRates(frameRates)))
                .followedBy(optional().character('-'))
                .then().exactly(1).of(anyOf(literal("p"), literal("fps")));

        addSiftPattern(ctx, frStandalone, frOpts, MatchName.FRAME_RATE);
    }

    private SiftPattern<Fragment> anyOfFrameRates(List<String> items) {
        if (items.size() == 1) {
            return parseFrameRate(items.getFirst());
        }
        return anyOf(items.stream().map(this::parseFrameRate).toList());
    }

    private SiftPattern<Fragment> parseFrameRate(String frConfig) {
        String optZeros = "(?:\\.0{1,3})?";

        if (frConfig.endsWith(optZeros)) {
            String base = frConfig.replace(optZeros, "");
            var zerosBlock = exactly(1).character('.').then().between(1, 3).character('0');
            return exactly(1).of(literal(base)).followedBy(optional().of(zerosBlock));
        }

        return literal(frConfig.replace("\\.", "."));
    }

    private SiftPattern<Fragment> anyOfList(List<String> items) {
        if (items.size() == 1) {
            return literal(items.getFirst());
        }
        return anyOf(items.stream().map(SiftPatterns::literal).toList());
    }

    private void addSiftPattern(ParseContext ctx, SiftPattern<Fragment> fragment, RegexOpts opts, MatchName matchName) {
        var sift = fromAnywhere().of(fragment);
        String rawRegex = sift.shake();

        Pattern p = patternCache.computeIfAbsent(rawRegex, s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE));

        for (var m : PatternMatcher.regex(ctx.input, p, matchName, opts, ctx.trace)) {
            ctx.matches.add(m);
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var section = ctx.config.section(SCREEN_SIZE);
        var standardHeights = new HashSet<>(stringList(section.get("progressive")));
        double minAr = ((Number) section.getOrDefault("min_ar", 1.333)).doubleValue();
        double maxAr = ((Number) section.getOrDefault("max_ar", 1.898)).doubleValue();

        normalizeScreenSizeMatches(ctx, standardHeights, minAr, maxAr);
        resolveWeakScreenSizeConflicts(ctx);
        extractFrameRatesFromScreenSize(ctx);
        keepOnlyLastDistinctScreenSize(ctx);
    }

    private void normalizeScreenSizeMatches(ParseContext ctx, Set<String> standardHeights, double minAr, double maxAr) {
        for (var m : ctx.matches.named(MatchName.SCREEN_SIZE).toList()) {
            if (m.tags().contains(NORMALIZED)) continue;

            var wh = WIDTH_HEIGHT_NORM.matcher(m.raw());
            if (wh.find()) {
                normalizeWidthHeightMatch(ctx, m, wh, standardHeights, minAr, maxAr);
            } else {
                var hs = HEIGHT_SCAN_NORM.matcher(m.raw());
                if (hs.find()) {
                    normalizeHeightScanMatch(ctx, m, hs);
                }
            }
        }
    }

    private void normalizeWidthHeightMatch(ParseContext ctx, Match m, java.util.regex.Matcher wh,
                                           Set<String> standardHeights, double minAr, double maxAr) {
        int w = Integer.parseInt(wh.group(GRP_WIDTH));
        int h = Integer.parseInt(wh.group(GRP_HEIGHT));
        String scan = wh.group(GRP_SCAN) == null ? "p" : wh.group(GRP_SCAN).toLowerCase(Locale.ROOT);
        double ar = (double) w / h;

        ctx.matches.add(new Match(MatchName.ASPECT_RATIO, Math.round(ar * 1000.0) / 1000.0,
                m.start(), m.end(), m.raw(), m.priority(), Set.of("derivedFrom:screen_size"), false));

        String value = (standardHeights.contains(String.valueOf(h)) && minAr < ar && ar < maxAr)
                ? h + scan : w + "x" + h;
        Set<String> tags = m.tags().contains(WEAK_SCREEN_SIZE)
                ? Set.of(NORMALIZED, WEAK_SCREEN_SIZE) : Set.of(NORMALIZED);

        ctx.matches.replace(m, new Match(MatchName.SCREEN_SIZE, value, m.start(), m.end(), m.raw(),
                m.priority(), tags, false));
    }

    private void normalizeHeightScanMatch(ParseContext ctx, Match m, java.util.regex.Matcher hs) {
        String h = hs.group(GRP_HEIGHT);
        String scan = hs.group(GRP_SCAN) == null ? "p" : hs.group(GRP_SCAN).toLowerCase(Locale.ROOT);
        Set<String> tags = m.tags().contains(WEAK_SCREEN_SIZE)
                ? Set.of(NORMALIZED, WEAK_SCREEN_SIZE) : Set.of(NORMALIZED);

        ctx.matches.replace(m, new Match(MatchName.SCREEN_SIZE, h + scan, m.start(), m.end(), m.raw(),
                m.priority(), tags, false));
    }

    private void resolveWeakScreenSizeConflicts(ParseContext ctx) {
        var weakSizes = ctx.matches.named(MatchName.SCREEN_SIZE)
                .filter(m -> m.tags().contains(WEAK_SCREEN_SIZE))
                .toList();

        if (weakSizes.isEmpty()) return;

        var strongNames = Set.of(MatchName.DATE, MatchName.SOURCE, MatchName.OTHER,
                MatchName.STREAMING_SERVICE, MatchName.VIDEO_PROFILE);
        var allMatches = ctx.matches.all().toList();

        for (var ws : weakSizes) {
            if (!hasStrongNeighbor(ws, allMatches, strongNames, ctx.input)) {
                ctx.matches.remove(ws);
                restoreWeakEpisodeIfNeeded(ctx, ws);
            }
        }
    }

    private boolean hasStrongNeighbor(Match ws, List<Match> allMatches, Set<MatchName> strongNames, String input) {
        for (var n : allMatches) {
            if (n == ws || !strongNames.contains(n.name())) continue;

            if (n.end() <= ws.start() && isGapOnlySeparators(input, n.end(), ws.start())) return true;
            if (n.start() >= ws.end() && isGapOnlySeparators(input, ws.end(), n.start())) return true;
        }
        return false;
    }

    private boolean isGapOnlySeparators(String input, int start, int end) {
        return input.substring(start, end).chars().allMatch(c -> Seps.isSep((char) c));
    }

    private void restoreWeakEpisodeIfNeeded(ParseContext ctx, Match ws) {
        boolean hasEpHere = ctx.matches.named(MatchName.EPISODE)
                .anyMatch(e -> e.start() == ws.start() && e.end() == ws.end());

        if (!hasEpHere && !"movie".equals(ctx.options.type())) {
            String raw = ws.raw();

            if (!raw.isEmpty() && raw.chars().allMatch(Character::isDigit)) {
                int v = Integer.parseInt(raw);
                if (v >= 100 || io.guessit.rules.property.WeakEpisodeExtractor.EPISODE.equals(ctx.options.type())
                        || ctx.options.episodePreferNumber() != null) {
                    ctx.matches.add(new Match(MatchName.EPISODE, v, ws.start(), ws.end(),
                            raw, 800, Set.of("weak-episode"), false));
                }
            }
        }
    }

    private void extractFrameRatesFromScreenSize(ParseContext ctx) {
        boolean hasFrameRate = ctx.matches.all().anyMatch(m -> m.name() == MatchName.FRAME_RATE);
        if (hasFrameRate) return;

        for (var m : ctx.matches.named(MatchName.SCREEN_SIZE).toList()) {
            var fr = FRAME_RATE_PATTERN.matcher(m.raw());
            if (fr.find()) {
                var rawFr = fr.group(GRP_FRAME_RATE);

                int dotIdx = rawFr.indexOf('.');
                int val = Integer.parseInt(dotIdx == -1 ? rawFr : rawFr.substring(0, dotIdx));

                ctx.matches.add(new Match(MatchName.FRAME_RATE, val,
                        m.start() + fr.start(GRP_FRAME_RATE), m.start() + fr.end(GRP_FRAME_RATE),
                        rawFr, m.priority(), Set.of("coexist", "derivedFrom:screen_size"), false));
            }
        }
    }

    private void keepOnlyLastDistinctScreenSize(ParseContext ctx) {
        for (var filePart : ctx.markers) {
            if (!"path".equals(filePart.name()) && !"whole".equals(filePart.name())) continue;

            var inPart = ctx.matches.named(MatchName.SCREEN_SIZE)
                    .filter(m -> filePart.covers(m.start(), m.end()))
                    .sorted((a, b) -> a.start() != b.start()
                            ? Integer.compare(b.start(), a.start())
                            : Integer.compare(b.end(), a.end()))
                    .toList();

            if (inPart.size() > 1) {
                long distinct = inPart.stream().map(m -> String.valueOf(m.value())).distinct().count();
                if (distinct > 1) {
                    inPart.subList(1, inPart.size()).forEach(ctx.matches::remove);
                }
            }
        }
    }

    private static List<String> stringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }
        return List.of();
    }
}