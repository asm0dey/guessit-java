package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    @Override public String name() { return SCREEN_SIZE; }

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
        var whP = Pattern.compile("(?<width>\\d{3,4})-?\\s*[x*]\\s*-?(?<height>\\d{3,4})", Pattern.CASE_INSENSITIVE);
        for (var m : PatternMatcher.regex(input, whP, SCREEN_SIZE, opts)) {
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
            .withTags(Set.of("weak.screen_size"));
        addRegex(ctx, resPrefix + heightP, weakOpts);

        // 4k literal → 2160p
        var fourK = StringOpts.defaults().withValidator(validator);
        for (var m : PatternMatcher.string(input, Set.of("4k"), SCREEN_SIZE, fourK)) {
            ctx.matches.add(new Match(SCREEN_SIZE, "2160p", m.start(), m.end(), m.raw(),
                m.priority(), Set.of("normalized"), false));
        }

        // frame_rate standalone, with mandatory `p` or `fps` suffix.
        var frP = Pattern.compile("(?<value>" + fr + ")-?(?:p|fps)", Pattern.CASE_INSENSITIVE);
        var frOpts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s.replaceAll("\\..*$", "")))
            .withTags(Set.of("coexist"))
            .withValidator(validator);
        for (var m : PatternMatcher.regex(input, frP, "frame_rate", frOpts)) {
            ctx.matches.add(m);
        }
    }

    /** Frame rate digits trailing a height+scan, e.g. "1080p24" → "24". */
    private static final Pattern FRAME_RATE_PATTERN =
        Pattern.compile("\\d{3,4}[ip](?<fr>\\d{2}(?:\\.\\d{1,3})?)$", Pattern.CASE_INSENSITIVE);

    private static void addRegex(ParseContext ctx, String src, RegexOpts opts) {
        var p = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        for (var m : PatternMatcher.regex(ctx.input, p, SCREEN_SIZE, opts)) {
            ctx.matches.add(m);
        }
    }

    /** PostProcessScreenSize + ScreenSizeOnlyOne. ResolveScreenSizeConflicts deferred to Plan 3. */
    @Override
    public void postProcess(ParseContext ctx) {
        var section = ctx.config.section(SCREEN_SIZE);
        var standardHeights = new HashSet<>(stringList(section.get("progressive")));
        double minAr = ((Number) section.getOrDefault("min_ar", 1.333)).doubleValue();
        double maxAr = ((Number) section.getOrDefault("max_ar", 1.898)).doubleValue();

        // PostProcessScreenSize: parse raw with named groups via regex re-match on raw text.
        var widthHeight = Pattern.compile("(?<width>\\d{3,4})\\s*[x*-]\\s*(?<height>\\d{3,4})(?<scan>[ip])?", Pattern.CASE_INSENSITIVE);
        var heightScan  = Pattern.compile("(?<height>\\d{3,4})(?<scan>[ip])?", Pattern.CASE_INSENSITIVE);

        for (var m : ctx.matches.named(SCREEN_SIZE).toList()) {
            if (m.tags().contains("normalized")) continue;
            boolean weak = m.tags().contains("weak.screen_size");
            var wh = widthHeight.matcher(m.raw());
            if (wh.find()) {
                int w = Integer.parseInt(wh.group("width"));
                int h = Integer.parseInt(wh.group("height"));
                String scan = wh.group("scan") == null ? "p" : wh.group("scan").toLowerCase(Locale.ROOT);
                double ar = (double) w / h;
                ctx.matches.add(new Match("aspect_ratio", Math.round(ar * 1000.0) / 1000.0,
                    m.start(), m.end(), m.raw(), m.priority(), Set.of("derivedFrom:screen_size"), false));
                String value = (standardHeights.contains(String.valueOf(h)) && minAr < ar && ar < maxAr)
                    ? h + scan : w + "x" + h;
                Set<String> nt = weak ? Set.of("normalized", "weak.screen_size") : Set.of("normalized");
                ctx.matches.replace(m, new Match(SCREEN_SIZE, value, m.start(), m.end(), m.raw(),
                    m.priority(), nt, false));
                continue;
            }
            var hs = heightScan.matcher(m.raw());
            if (hs.find()) {
                String h = hs.group("height");
                String scan = hs.group("scan") == null ? "p" : hs.group("scan").toLowerCase(Locale.ROOT);
                Set<String> nt = weak ? Set.of("normalized", "weak.screen_size") : Set.of("normalized");
                ctx.matches.replace(m, new Match(SCREEN_SIZE, h + scan, m.start(), m.end(), m.raw(),
                    m.priority(), nt, false));
            }
        }

        // ResolveScreenSizeConflicts (port of python rule): drop weak.screen_size
        // matches that lack a strong neighbor (date/source/other/streaming_service/
        // video_profile) — i.e. bare "720"/"1080" digits without resolution context.
        var weakSizes = ctx.matches.named(SCREEN_SIZE)
            .filter(m -> m.tags().contains("weak.screen_size"))
            .toList();
        if (!weakSizes.isEmpty()) {
            var strongNames = Set.of("date", "source", "other", "streaming_service", "video_profile");
            var allMatches = ctx.matches.all().toList();
            for (var ws : weakSizes) {
                boolean hasNeighbor = false;
                for (var n : allMatches) {
                    if (n == ws) continue;
                    if (!strongNames.contains(n.name())) continue;
                    if (n.end() <= ws.start()) {
                        var gap = ctx.input.substring(n.end(), ws.start());
                        if (gap.chars().allMatch(c -> Seps.isSep((char) c))) { hasNeighbor = true; break; }
                    } else if (n.start() >= ws.end()) {
                        var gap = ctx.input.substring(ws.end(), n.start());
                        if (gap.chars().allMatch(c -> Seps.isSep((char) c))) { hasNeighbor = true; break; }
                    }
                }
                if (!hasNeighbor) {
                    ctx.matches.remove(ws);
                    // Restore: bare height that lost overlap to season/episode is
                    // dropped (matches python's screensize-loses-to-season-episode).
                    // Re-emit weak_episode at this span if no episode covers it,
                    // honoring episode_prefer_number gating.
                    boolean hasEpHere = ctx.matches.named("episode")
                        .anyMatch(e -> e.start() == ws.start() && e.end() == ws.end());
                    if (!hasEpHere && !"movie".equals(ctx.options.type())) {
                        try {
                            int v = Integer.parseInt(ws.raw());
                            if (v >= 100 || io.guessit.rules.property.WeakEpisodeExtractor.EPISODE.equals(ctx.options.type())
                                    || ctx.options.episodePreferNumber() != null) {
                                ctx.matches.add(new Match("episode", v, ws.start(), ws.end(),
                                    ws.raw(), 800, Set.of("weak-episode"), false));
                            }
                        } catch (NumberFormatException _) { }
                    }
                }
            }
        }

        // Extract frame_rate from surviving screen_size raw strings (e.g., "1080p24" → "24").
        var allNames = ctx.matches.all().map(Match::name).toList();
        //noinspection StatementWithEmptyBody
        if (allNames.stream().anyMatch(n -> n.equals("frame_rate"))) {
            // Already have a standalone frame_rate match — no need to re-parse.
        } else {
            for (var m : ctx.matches.named(SCREEN_SIZE).toList()) {
                var fr = FRAME_RATE_PATTERN.matcher(m.raw());
                if (fr.find()) {
                    var rawFr = fr.group("fr");
                    int val = Integer.parseInt(rawFr.replaceAll("\\..*$", ""));
                    ctx.matches.add(new Match("frame_rate", val,
                        m.start() + fr.start("fr"), m.start() + fr.end("fr"),
                        rawFr, m.priority(), Set.of("coexist", "derivedFrom:screen_size"), false));
                }
            }
        }

        // ScreenSizeOnlyOne: per filepart, keep last screen_size only when distinct values present.
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name()) && !"whole".equals(filepart.name())) continue;
            var inPart = ctx.matches.named(SCREEN_SIZE)
                .filter(m -> filepart.covers(m.start(), m.end()))
                .sorted((a, b) -> {
                    if (a.start() != b.start()) return Integer.compare(b.start(), a.start());
                    return Integer.compare(b.end(), a.end());
                })
                .toList();
            if (inPart.size() <= 1) continue;
            var distinct = inPart.stream().map(m -> String.valueOf(m.value())).distinct().count();
            if (distinct > 1) {
                for (int i = 1; i < inPart.size(); i++) {
                    ctx.matches.remove(inPart.get(i));
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
