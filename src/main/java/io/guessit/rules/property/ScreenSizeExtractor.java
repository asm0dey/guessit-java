package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Extracts {@code screen_size} (e.g. {@code 1080p}, {@code 2160p}, {@code 720x576})
 * and the related {@code aspect_ratio} and {@code frame_rate} properties.
 *
 * <p>Two passes:
 * <ol>
 *   <li>{@link #extract} — recognises the common shapes: width×height,
 *       height+scan ({@code 1080p}, {@code 1080i}), height+scan+frame_rate
 *       ({@code 1080p24}), the {@code 4k} alias, and standalone frame rates.</li>
 *   <li>{@link #postProcess} — normalises raw spans into canonical values
 *       (computing aspect ratios from W×H, choosing {@code "1080p"} vs
 *       {@code "1920x1080"} based on whether the AR falls in the 16:9-ish
 *       envelope), extracts frame rates piggy-backed on screen sizes, and
 *       drops duplicate screen sizes within a filepart.</li>
 * </ol>
 */
public final class ScreenSizeExtractor implements Extractor {

    public static final String SCREEN_SIZE = "screen_size";
    public static final String WEAK_SCREEN_SIZE = "weak.screen_size";
    public static final String NORMALIZED = "normalized";

    private static final Pattern WH_P = Pattern.compile("(?<width>\\d{3,4})-?\\s*[x*]\\s*-?(?<height>\\d{3,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIDTH_HEIGHT_NORM = Pattern.compile("(?<width>\\d{3,4})\\s*[x*-]\\s*(?<height>\\d{3,4})(?<scan>[ip])?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEIGHT_SCAN_NORM = Pattern.compile("(?<height>\\d{3,4})(?<scan>[ip])?", Pattern.CASE_INSENSITIVE);

    private final ConcurrentMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private Pattern compileCi(String src) {
        return patternCache.computeIfAbsent(src, s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE));
    }

    @Override public String name() { return SCREEN_SIZE; }

    @Override
    public String description() {
        return "resolution (480p / 720p / 1080p / 2160p / 4K, including i variants)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section(SCREEN_SIZE);
        var interlaced = stringList(section.get("interlaced"));
        var progressive = stringList(section.get("progressive"));
        var frameRates = stringList(section.get("frame_rates"));
        var input = ctx.input;

        var heightI = "(?<height>" + String.join("|", interlaced) + ")";
        var heightP = "(?<height>" + String.join("|", progressive) + ")";
        var fr = "(?:" + String.join("|", frameRates) + ")";
        var resPrefix = "(?:(?<width>\\d{3,4})(?:x|\\*))?";

        var validator = Validators.sepsSurround(input);
        var opts = RegexOpts.defaults().withValidator(validator);

        // width-x-height fallback for non-standard sizes — extract before height+scan
        // so WxH matches are last in MatchSet (ScreenSizeOnlyOne sorts by start DESC).
        for (var m : PatternMatcher.regex(input, WH_P, MatchName.SCREEN_SIZE, opts, ctx.trace)) {
            ctx.matches.add(m);
        }

        addRegex(ctx, resPrefix + heightI + "(?<scan>i)" + "(?:" + fr + ")?", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)" + "(?:" + fr + ")?", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)?(?:hd)", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)?x", opts);
        // Bare height pattern: 720, 1080, 2160, ... without `p`/`i`/`hd`/`x`. Tag
        // weak.screen_size so postProcess can drop matches lacking a strong
        // neighbor (date/source/other/streaming_service/video_profile).
        var weakOpts = RegexOpts.defaults()
            .withValidator(validator)
            .withTags(Set.of(WEAK_SCREEN_SIZE));
        addRegex(ctx, resPrefix + heightP, weakOpts);

        // 4k literal → 2160p
        var fourK = StringOpts.defaults().withValidator(validator);
        for (var m : PatternMatcher.string(input, Set.of("4k"), MatchName.SCREEN_SIZE, fourK, ctx.trace)) {
            ctx.matches.add(new Match(MatchName.SCREEN_SIZE, "2160p", m.start(), m.end(), m.raw(),
                m.priority(), Set.of(NORMALIZED), false));
        }

        // frame_rate standalone, with mandatory `p` or `fps` suffix.
        var frP = compileCi("(?<value>" + fr + ")-?(?:p|fps)");
        var frOpts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s.replaceAll("\\..*$", "")))
            .withTags(Set.of("coexist"))
            .withValidator(validator);
        for (var m : PatternMatcher.regex(input, frP, MatchName.FRAME_RATE, frOpts, ctx.trace)) {
            ctx.matches.add(m);
        }
    }

    /** Frame rate digits trailing a height+scan, e.g. "1080p24" → "24". */
    private static final Pattern FRAME_RATE_PATTERN =
        Pattern.compile("\\d{3,4}[ip](?<fr>\\d{2}(?:\\.\\d{1,3})?)$", Pattern.CASE_INSENSITIVE);

    private void addRegex(ParseContext ctx, String src, RegexOpts opts) {
        var p = compileCi(src);
        for (var m : PatternMatcher.regex(ctx.input, p, MatchName.SCREEN_SIZE, opts, ctx.trace)) {
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
        int w = Integer.parseInt(wh.group("width"));
        int h = Integer.parseInt(wh.group("height"));
        String scan = wh.group("scan") == null ? "p" : wh.group("scan").toLowerCase(Locale.ROOT);
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
        String h = hs.group("height");
        String scan = hs.group("scan") == null ? "p" : hs.group("scan").toLowerCase(Locale.ROOT);
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
            try {
                int v = Integer.parseInt(ws.raw());
                if (v >= 100 || io.guessit.rules.property.WeakEpisodeExtractor.EPISODE.equals(ctx.options.type())
                        || ctx.options.episodePreferNumber() != null) {
                    ctx.matches.add(new Match(MatchName.EPISODE, v, ws.start(), ws.end(),
                            ws.raw(), 800, Set.of("weak-episode"), false));
                }
            } catch (NumberFormatException _) {
                // weak-episode candidate had non-integer raw — skip silently.
            }
        }
    }

    private void extractFrameRatesFromScreenSize(ParseContext ctx) {
        boolean hasFrameRate = ctx.matches.all().anyMatch(m -> m.name() == MatchName.FRAME_RATE);
        if (hasFrameRate) return;

        for (var m : ctx.matches.named(MatchName.SCREEN_SIZE).toList()) {
            var fr = FRAME_RATE_PATTERN.matcher(m.raw());
            if (fr.find()) {
                var rawFr = fr.group("fr");
                int val = Integer.parseInt(rawFr.replaceAll("\\..*$", ""));
                ctx.matches.add(new Match(MatchName.FRAME_RATE, val,
                        m.start() + fr.start("fr"), m.start() + fr.end("fr"),
                        rawFr, m.priority(), Set.of("coexist", "derivedFrom:screen_size"), false));
            }
        }
    }

    private void keepOnlyLastDistinctScreenSize(ParseContext ctx) {
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name()) && !"whole".equals(filepart.name())) continue;

            var inPart = ctx.matches.named(MatchName.SCREEN_SIZE)
                    .filter(m -> filepart.covers(m.start(), m.end()))
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

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        return List.of();
    }
}
