package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;

public final class ReleaseGroupExtractor implements Extractor {
    private static final Set<String> SCENE_PREV = Set.of(
        "video_codec", "source", "video_api", "audio_codec", "audio_profile", "video_profile",
        "audio_channels", "screen_size", "other", "container",
        "language", "subtitle_language");

    @Override public String name() { return "release_group"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedGroup();
        if (expected.isEmpty()) return;
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        for (var name : expected) {
            int from = 0;
            var hay = input.toLowerCase();
            var n = name.toLowerCase();
            while (true) {
                int idx = hay.indexOf(n, from);
                if (idx < 0) break;
                int end = idx + name.length();
                var m = new Match("release_group", name, idx, end, input.substring(idx, end),
                    2000, Set.of("expected"), false);
                if (validator.test(m)) ctx.matches.add(m);
                from = idx + 1;
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named("release_group").findAny().isPresent()) return;
        if (detectDashSeparated(ctx)) return;
        if (detectScene(ctx)) return;
        detectAnimeBrackets(ctx);
    }

    private boolean detectDashSeparated(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var part = input.substring(filepart.start(), filepart.end());

            // Trailing dash group: "...-Group.ext"
            var ext = ctx.matches.named("container")
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains("extension"))
                .findFirst().orElse(null);
            int end = ext != null ? ext.start() : filepart.end();
            int dash = input.lastIndexOf('-', end - 1);
            if (dash > filepart.start() && dash < end - 1) {
                int s = dash + 1;
                int e = end;
                while (s < e && Seps.isSep(input.charAt(s))) s++;
                while (e > s && Seps.isSep(input.charAt(e - 1))) e--;
                if (s < e) {
                    var candidate = input.substring(s, e);
                    if (validGroupName(candidate) && !overlapsNonLanguage(ctx, s, e)) {
                        removeOverlappingLanguages(ctx, s, e);
                        ctx.matches.add(new Match("release_group", candidate, s, e,
                            candidate, 1500, Set.of("scene"), false));
                        return true;
                    }
                }
            }

            // Leading dash group: "abc-the.title..."
            int firstDash = part.indexOf('-');
            if (firstDash > 0 && firstDash < part.length() - 1) {
                var candidate = part.substring(0, firstDash);
                var rest = part.substring(firstDash + 1);
                if (validGroupName(candidate) && rest.contains(".") && !rest.contains(" ")) {
                    int absStart = filepart.start();
                    int absEnd = filepart.start() + firstDash;
                    if (!overlapsNonLanguage(ctx, absStart, absEnd)) {
                        removeOverlappingLanguages(ctx, absStart, absEnd);
                        ctx.matches.add(new Match("release_group", candidate, absStart, absEnd,
                            candidate, 1500, Set.of("scene"), false));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean detectScene(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var ext = ctx.matches.named("container")
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains("extension"))
                .findFirst().orElse(null);
            int rangeEnd = ext != null ? ext.start() : filepart.end();

            var prev = ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
            if (prev == null) continue;

            var gap = input.substring(prev.end(), rangeEnd);
            int leadSeps = 0;
            while (leadSeps < gap.length() && Seps.isSep(gap.charAt(leadSeps))) leadSeps++;
            int trailSeps = 0;
            while (trailSeps < gap.length() - leadSeps && Seps.isSep(gap.charAt(gap.length() - 1 - trailSeps))) trailSeps++;
            int s = prev.end() + leadSeps;
            int e = rangeEnd - trailSeps;
            if (e <= s) continue;
            var trimmed = input.substring(s, e);
            if (!validGroupName(trimmed)) continue;
            if (overlapsNonLanguage(ctx, s, e)) continue;
            removeOverlappingLanguages(ctx, s, e);
            ctx.matches.add(new Match("release_group", trimmed, s, e, trimmed, 1500, Set.of("scene"), false));
            return true;
        }
        return false;
    }

    private boolean detectAnimeBrackets(ParseContext ctx) {
        for (var marker : ctx.markers) {
            if (!"group".equals(marker.name())) continue;
            var raw = marker.raw();
            String innerStr = raw;
            int innerS = marker.start();
            int innerE = marker.end();
            if (raw.length() >= 2 && (raw.charAt(0) == '[' || raw.charAt(0) == '(')) {
                innerStr = raw.substring(1, raw.length() - 1);
                innerS = marker.start() + 1;
                innerE = marker.end() - 1;
            }
            final int fInnerS = innerS;
            final int fInnerE = innerE;
            var trimmed = innerStr.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.chars().allMatch(Character::isDigit)) continue;
            boolean hasOtherInside = ctx.matches.all()
                .filter(m -> !m.name().equals("language") && !m.name().equals("subtitle_language"))
                .anyMatch(m -> m.start() >= fInnerS && m.end() <= fInnerE);
            if (hasOtherInside) continue;
            ctx.matches.add(new Match("release_group", trimmed, fInnerS, fInnerE,
                innerStr, 1500, Set.of("anime"), false));
            return true;
        }
        return false;
    }

    private static boolean overlapsNonLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> !m.name().equals("language") && !m.name().equals("subtitle_language"))
            .anyMatch(m -> m.start() < e && s < m.end());
    }

    private static void removeOverlappingLanguages(ParseContext ctx, int s, int e) {
        var toRemove = ctx.matches.all()
            .filter(m -> m.name().equals("language") || m.name().equals("subtitle_language"))
            .filter(m -> m.start() < e && s < m.end())
            .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean validGroupName(String s) {
        var t = s.trim();
        if (t.length() < 2) return false;
        if (t.contains(" ")) return false;
        if (t.chars().allMatch(Character::isDigit)) return false;
        return true;
    }
}
