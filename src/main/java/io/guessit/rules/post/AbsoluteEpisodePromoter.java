package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.*;

/**
 * PostPhase processor that finalises {@code absolute_episode} promotion after
 * {@link EpisodeNumberSeparatorRange} and {@link RangeFiller} have expanded all
 * bare episode ranges.
 *
 * <p>Two sub-rules:
 * <ol>
 *   <li><b>Absolute range fill:</b> when two adjacent {@code absolute_episode}
 *       matches are separated only by a range separator in the text (e.g.
 *       {@code 313-315}), add {@code absolute_episode} for every intermediate
 *       value. This handles the case where
 *       {@link io.guessit.rules.property.WeakEpisodeExtractor} renamed the
 *       boundary values before {@code EpisodeNumberSeparatorRange} could
 *       expand the text range.</li>
 *   <li><b>Group-marker absolute:</b> when the filepart contains no
 *       {@code SxxExx}-tagged episodes but has exactly two multi-episode
 *       groups (one enclosed in brackets/parens and one not), the higher-
 *       position group is renamed to {@code absolute_episode}. Handles anime
 *       torrent names like
 *       {@code [Group]_Show_-_16-20_(191-195)_[720p].torrent}.</li>
 * </ol>
 *
 * <p>Must run after {@link EpisodeNumberSeparatorRange} so that all range
 * expansions in both the episode and absolute_episode spans are complete.
 */
public final class AbsoluteEpisodePromoter implements PostPhase.PostProcessor {

    private static final int MAX_ABS_RANGE = 20;

    @Override
    public void process(ParseContext ctx) {
        absoluteRangeFill(ctx);
        groupMarkerAbsolute(ctx);
    }

    /**
     * For each pair of adjacent {@code absolute_episode} matches where the
     * text between them is a range separator, fills in the missing intermediate
     * values as additional {@code absolute_episode} matches.
     */
    private static void absoluteRangeFill(ParseContext ctx) {
        var absEps = ctx.matches.named("absolute_episode")
            .filter(m -> m.value() instanceof Integer)
            .sorted(Comparator.comparingInt(Match::start))
            .toList();

        var fills = new ArrayList<Match>();
        for (int i = 0; i + 1 < absEps.size(); i++) {
            var a = absEps.get(i);
            var b = absEps.get(i + 1);
            int va = (Integer) a.value();
            int vb = (Integer) b.value();
            if (vb <= va + 1) continue;               // no gap
            if (vb - va - 1 > MAX_ABS_RANGE) continue; // sanity cap

            // Gap must be a pure range-separator (e.g. "-")
            if (b.start() <= a.end()) continue;
            var gap = ctx.input.substring(a.end(), b.start());
            if (!isSepRange(gap)) continue;

            // Insert intermediates at the same position as b (zero-width).
            for (int v = va + 1; v < vb; v++) {
                fills.add(new Match("absolute_episode", v,
                    b.start(), b.start(),
                    "", a.priority(), Set.of("range-fill"), false));
            }
        }
        for (var m : fills) ctx.matches.add(m);
    }

    /**
     * True when the string consists only of separator characters plus at most
     * one range-marker ({@code -}, {@code ~}, {@code to}).
     */
    private static boolean isSepRange(String gap) {
        // Allow pure-sep padding around the range marker.
        // Pattern: [seps]* ('-' or '~' or 'to') [seps]*
        var lower = gap.strip().toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty()) return false;
        if (lower.equals("-") || lower.equals("~") || lower.equals("to")) return true;
        // Check if whole string is seps + exactly one range marker surrounded by seps.
        var trimmed = strip(gap);
        return trimmed.equals("-") || trimmed.equals("~") || trimmed.equals("to");
    }

    private static String strip(String s) {
        int start = 0;
        while (start < s.length() && Seps.isSep(s.charAt(start)) && s.charAt(start) != '-'
               && s.charAt(start) != '~') start++;
        int end = s.length();
        while (end > start && Seps.isSep(s.charAt(end - 1)) && s.charAt(end - 1) != '-'
               && s.charAt(end - 1) != '~') end--;
        return s.substring(start, end);
    }

    /**
     * For each path filepart that has no {@code SxxExx}-tagged episode, group
     * the surviving {@code episode} matches by enclosing group marker. If
     * exactly two multi-episode groups result and the gap between them is all
     * separators, rename the higher-position group to {@code absolute_episode}.
     */
    private static void groupMarkerAbsolute(ParseContext ctx) {
        var pathMarkers = Markers.named(ctx.markers, "path").toList();
        for (var fp : pathMarkers) {
            // Only act on fileparts with no SxxExx episodes.
            boolean hasSxxExx = ctx.matches.named("episode")
                .anyMatch(m -> m.tags().contains("SxxExx")
                    && m.start() >= fp.start() && m.end() <= fp.end());
            if (hasSxxExx) continue;

            // Collect episode matches in this filepart sorted by start.
            var eps = ctx.matches.snapshot().stream()
                .filter(m -> "episode".equals(m.name())
                    && !m.isPrivate()
                    && m.start() >= fp.start() && m.end() <= fp.end())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
            if (eps.isEmpty()) continue;

            // Group by enclosing group marker. Episodes with no enclosing marker
            // are coalesced into a "contiguous run" group: any episode that is
            // adjacent (sep-only gap) to the most-recent non-enc group's last
            // member joins that group rather than starting its own.
            var groups = new LinkedHashMap<Object, List<Match>>();
            Object lastNonEncKey = null;
            for (var e : eps) {
                var enc = ctx.markers.stream()
                    .filter(g -> "group".equals(g.name())
                        && g.start() <= e.start() && g.end() >= e.end())
                    .findFirst().orElse(null);
                Object key;
                if (enc != null) {
                    // Episode is inside a bracket/paren group: use the marker as key.
                    key = enc;
                } else if (e.tags().contains("range-fill")) {
                    // Range-fill: always coalesce into the most-recently-opened group.
                    key = groups.keySet().stream()
                        .reduce((_, b) -> b).orElse("noenc-" + e.start());
                    if (key.toString().startsWith("noenc-")) lastNonEncKey = key;
                } else {
                    // Regular non-enc episode: coalesce with the previous non-enc run
                    // if the gap to the run's last member is separator-only (≤10 chars).
                    key = "noenc-" + e.start(); // default: new group
                    if (lastNonEncKey != null) {
                        var prevGroup = groups.get(lastNonEncKey);
                        if (prevGroup != null && !prevGroup.isEmpty()) {
                            int prevEnd = prevGroup.stream().mapToInt(Match::end).max().orElse(0);
                            if (prevEnd <= e.start()) {
                                var gapText = ctx.input.substring(prevEnd, e.start());
                                if (gapText.length() <= 10
                                        && gapText.chars().allMatch(c -> Seps.isSep((char) c))) {
                                    key = lastNonEncKey; // join the run
                                }
                            }
                        }
                    }
                    lastNonEncKey = key;
                }
                groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(e);
            }

            // Keep only groups with ≥ 2 members, sorted by max end position.
            var multi = groups.values().stream()
                .filter(g -> g.size() >= 2)
                .sorted(Comparator.comparingInt(g -> g.stream().mapToInt(Match::end).max().orElse(0)))
                .toList();

            if (multi.size() != 2) continue;

            var lower = multi.get(0);
            var higher = multi.get(1);

            // Verify that the gap between the two groups is separator-only.
            // Use max end of lower group and min start of higher group to handle
            // zero-width range-fill matches that may appear after the real boundary.
            int gapStart = lower.stream().mapToInt(Match::end).max().orElse(0);
            int gapEnd   = higher.stream().mapToInt(Match::start).min().orElse(ctx.input.length());
            if (gapEnd < gapStart) continue;
            var gap = ctx.input.substring(gapStart, gapEnd);
            boolean sepOnly = gap.chars().allMatch(c -> Seps.isSep((char) c));
            if (!sepOnly) continue;

            // Equal count required (mirrors Python RenameToAbsoluteEpisode).
            if (lower.size() != higher.size()) continue;

            // Rename the higher group to absolute_episode.
            for (var m : higher) {
                ctx.matches.replace(m, m.withName("absolute_episode"));
            }
        }
    }
}
