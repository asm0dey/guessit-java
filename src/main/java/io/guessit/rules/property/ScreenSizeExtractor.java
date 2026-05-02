package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class ScreenSizeExtractor implements Extractor {

    @Override public String name() { return "screen_size"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("screen_size");
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

        addRegex(ctx, resPrefix + heightI + "(?<scan>i)" + "(?:" + fr + ")?", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)" + "(?:" + fr + ")?", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)?(?:hd)", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)?x", opts);

        // 4k literal → 2160p
        var fourK = StringOpts.defaults().withValidator(validator);
        for (var m : PatternMatcher.string(input, Set.of("4k"), "screen_size", fourK)) {
            ctx.matches.add(new Match("screen_size", "2160p", m.start(), m.end(), m.raw(),
                m.priority(), Set.of("normalized"), false));
        }

        // width-x-height fallback for non-standard sizes
        var whP = Pattern.compile("(?<width>\\d{3,4})-?(?:x|\\*)-?(?<height>\\d{3,4})", Pattern.CASE_INSENSITIVE);
        for (var m : PatternMatcher.regex(input, whP, "screen_size", opts)) {
            // Replace the value with raw "WxH" — PostProcess will normalize again.
            ctx.matches.add(m);
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
        for (var m : PatternMatcher.regex(ctx.input, p, "screen_size", opts)) {
            ctx.matches.add(m);
        }
    }

    /** PostProcessScreenSize + ScreenSizeOnlyOne. ResolveScreenSizeConflicts deferred to Plan 3. */
    @Override
    public void postProcess(ParseContext ctx) {
        var section = ctx.config.section("screen_size");
        var standardHeights = new HashSet<>(stringList(section.get("progressive")));
        double minAr = ((Number) section.getOrDefault("min_ar", 1.333)).doubleValue();
        double maxAr = ((Number) section.getOrDefault("max_ar", 1.898)).doubleValue();

        // PostProcessScreenSize: parse raw with named groups via regex re-match on raw text.
        var widthHeight = Pattern.compile("(?<width>\\d{3,4})[x*-](?<height>\\d{3,4})", Pattern.CASE_INSENSITIVE);
        var heightScan  = Pattern.compile("(?<height>\\d{3,4})(?<scan>[ip])?", Pattern.CASE_INSENSITIVE);
        var toReplace = new ArrayList<Match[]>();
        for (var m : ctx.matches.named("screen_size").toList()) {
            if (m.tags().contains("normalized")) continue;
            var wh = widthHeight.matcher(m.raw());
            if (wh.find()) {
                int w = Integer.parseInt(wh.group("width"));
                int h = Integer.parseInt(wh.group("height"));
                double ar = (double) w / h;
                ctx.matches.add(new Match("aspect_ratio", Math.round(ar * 1000.0) / 1000.0,
                    m.start(), m.end(), m.raw(), m.priority(), Set.of(), false));
                String value = (standardHeights.contains(String.valueOf(h)) && minAr < ar && ar < maxAr)
                    ? h + "p" : w + "x" + h;
                toReplace.add(new Match[]{ m, m.withTags(Set.of("normalized")) });
                ctx.matches.replace(m, new Match("screen_size", value, m.start(), m.end(), m.raw(),
                    m.priority(), Set.of("normalized"), false));
                continue;
            }
            var hs = heightScan.matcher(m.raw());
            if (hs.find()) {
                String h = hs.group("height");
                String scan = hs.group("scan") == null ? "p" : hs.group("scan").toLowerCase(Locale.ROOT);
                ctx.matches.replace(m, new Match("screen_size", h + scan, m.start(), m.end(), m.raw(),
                    m.priority(), Set.of("normalized"), false));
            }
        }

        // Extract frame_rate from surviving screen_size raw strings (e.g., "1080p24" → "24").
        var allNames = ctx.matches.all().map(Match::name).toList();
        if (allNames.stream().anyMatch(n -> n.equals("frame_rate"))) {
            // Already have a standalone frame_rate match — no need to re-parse.
        } else {
            for (var m : ctx.matches.named("screen_size").toList()) {
                var fr = FRAME_RATE_PATTERN.matcher(m.raw());
                if (fr.find()) {
                    var rawFr = fr.group("fr");
                    int val = Integer.parseInt(rawFr.replaceAll("\\..*$", ""));
                    ctx.matches.add(new Match("frame_rate", val,
                        m.start() + fr.start("fr"), m.start() + fr.end("fr"),
                        rawFr, m.priority(), Set.of("coexist"), false));
                }
            }
        }

        // ScreenSizeOnlyOne: per filepart, keep last screen_size only when distinct values present.
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var inPart = ctx.matches.named("screen_size")
                .filter(m -> filepart.covers(m.start(), m.end()))
                .sorted(Comparator.comparingInt(Match::start).reversed())
                .toList();
            if (inPart.size() <= 1) continue;
            var distinct = inPart.stream().map(m -> String.valueOf(m.value())).distinct().count();
            if (distinct > 1) {
                for (int i = 1; i < inPart.size(); i++) ctx.matches.remove(inPart.get(i));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        return List.of();
    }
}
