package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReleaseGroupExtractor implements Extractor {
    private static final Set<String> SCENE_PREV = Set.of(
        "video_codec", "source", "video_api", "audio_codec", "audio_profile", "video_profile",
        "audio_channels", "screen_size", "other", "container",
        "language", "subtitle_language", "year");

    /** Forbidden release-group prefix/suffix names (from config: release_group.forbidden_names). */
    private static final List<String> FORBIDDEN_NAMES = List.of("bonus", "by", "for", "par", "pour", "rip");

    /** Separators that are NOT stripped from group names (config: release_group.ignored_seps). */
    private static final String IGNORED_SEPS = "[]{}()";

    /** Combined parens+brackets pattern: "name) [tag]" → "name tag". Mirrors Python clean_groupname. */
    private static final Pattern PARENS_BRACKETS = Pattern.compile("(.+)\\)\\s?\\[(.+)]");

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
                while (s < e && isGroupSep(input.charAt(s))) s++;
                while (e > s && isGroupSep(input.charAt(e - 1))) e--;
                if (s < e) {
                    var raw = input.substring(s, e);
                    var candidate = cleanGroupName(raw);
                    if (validGroupName(candidate, false) && !overlapsNonLanguage(ctx, s, e)
                        && !overlapsSubtitleLanguage(ctx, s, e)) {
                        if (isProbableLanguagePrefix(candidate)) continue;
                        removeOverlappingLanguages(ctx, s, e);
                        ctx.matches.add(new Match("release_group", candidate, s, e,
                            raw, 1500, Set.of("scene"), false));
                        return true;
                    }
                }
            }

            // Leading dash group: "abc-the.title..."
            int firstDash = part.indexOf('-');
            if (firstDash > 0 && firstDash < part.length() - 1) {
                var rawCandidate = part.substring(0, firstDash);
                var candidate = cleanGroupName(rawCandidate);
                var rest = part.substring(firstDash + 1);
                if (validGroupName(candidate, false) && rest.contains(".") && !rest.contains(" ")) {
                    int absStart = filepart.start();
                    int absEnd = filepart.start() + firstDash;
                    if (!overlapsNonLanguage(ctx, absStart, absEnd)
                        && !overlapsSubtitleLanguage(ctx, absStart, absEnd)
                        && !overlapsLanguage(ctx, absStart, absEnd)) {
                        removeOverlappingLanguages(ctx, absStart, absEnd);
                        ctx.matches.add(new Match("release_group", candidate, absStart, absEnd,
                            rawCandidate, 1500, Set.of("scene"), false));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean overlapsSubtitleLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named("subtitle_language")
            .anyMatch(m -> m.start() < e && s < m.end());
    }

    private static boolean overlapsLanguage(ParseContext ctx, int s, int e) {
        return ctx.matches.named("language")
            .anyMatch(m -> m.start() < e && s < m.end());
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
            while (leadSeps < gap.length() && isGroupSep(gap.charAt(leadSeps))) leadSeps++;
            int trailSeps = 0;
            while (trailSeps < gap.length() - leadSeps && isGroupSep(gap.charAt(gap.length() - 1 - trailSeps))) trailSeps++;
            int s = prev.end() + leadSeps;
            int e = rangeEnd - trailSeps;
            if (e <= s) continue;
            var raw = input.substring(s, e);
            var candidate = cleanGroupName(raw);
            if (!validGroupName(candidate, true)) continue;
            if (isProbableLanguagePrefix(candidate)) continue;
            if (overlapsNonLanguage(ctx, s, e)) continue;
            if (overlapsSubtitleLanguage(ctx, s, e)) continue;
            removeOverlappingLanguages(ctx, s, e);
            ctx.matches.add(new Match("release_group", candidate, s, e, raw, 1500, Set.of("scene"), false));
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

    /** Group-aware separator: standard seps minus {@link #IGNORED_SEPS} (brackets/parens). */
    private static boolean isGroupSep(char c) {
        if (IGNORED_SEPS.indexOf(c) >= 0) return false;
        return Seps.isSep(c);
    }

    /**
     * Mirrors Python guessit's clean_groupname: strips group-seps, drops forbidden
     * prefix/suffix words (by, rip, …), and converts "name) [tag]" → "name tag".
     */
    static String cleanGroupName(String input) {
        var s = stripGroupSeps(input);
        // Conditional inner strip of brackets/parens: only drop when *not* needed for structure.
        if (!(endsWithIgnored(s) && startsWithIgnored(s))
            && !containsIgnored(stripIgnoredSeps(s))) {
            s = stripIgnoredSeps(s);
        }
        // Drop forbidden prefix/suffix names if separated from rest by a sep.
        for (var forbidden : FORBIDDEN_NAMES) {
            if (s.toLowerCase().startsWith(forbidden) && s.length() > forbidden.length()
                && Seps.isSep(s.charAt(forbidden.length()))) {
                s = stripGroupSeps(s.substring(forbidden.length()));
            }
            if (s.toLowerCase().endsWith(forbidden) && s.length() > forbidden.length()
                && Seps.isSep(s.charAt(s.length() - forbidden.length() - 1))) {
                s = stripGroupSeps(s.substring(0, s.length() - forbidden.length()));
            }
        }
        // "Individual) [Group]" → "Individual Group".
        var m = PARENS_BRACKETS.matcher(s.trim());
        if (m.matches()) {
            s = m.group(1) + " " + m.group(2);
        }
        return s.trim();
    }

    private static String stripGroupSeps(String s) {
        int i = 0, j = s.length();
        while (i < j && isGroupSep(s.charAt(i))) i++;
        while (j > i && isGroupSep(s.charAt(j - 1))) j--;
        return s.substring(i, j);
    }

    private static String stripIgnoredSeps(String s) {
        int i = 0, j = s.length();
        while (i < j && IGNORED_SEPS.indexOf(s.charAt(i)) >= 0) i++;
        while (j > i && IGNORED_SEPS.indexOf(s.charAt(j - 1)) >= 0) j--;
        return s.substring(i, j);
    }

    private static boolean startsWithIgnored(String s) {
        return !s.isEmpty() && IGNORED_SEPS.indexOf(s.charAt(0)) >= 0;
    }

    private static boolean endsWithIgnored(String s) {
        return !s.isEmpty() && IGNORED_SEPS.indexOf(s.charAt(s.length() - 1)) >= 0;
    }

    private static boolean containsIgnored(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (IGNORED_SEPS.indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static boolean validGroupName(String s, boolean allowSpaces) {
        var t = s.trim();
        if (t.length() < 2) return false;
        if (!allowSpaces && t.contains(" ")) return false;
        return !t.chars().allMatch(Character::isDigit);
    }

    private static boolean isProbableLanguagePrefix(String candidate) {
        var lower = candidate.toLowerCase();
        return lower.startsWith("sub") || lower.startsWith("st") || lower.endsWith("sub");
    }
}
