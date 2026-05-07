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
    private static final String NOENC_PREFIX = "noenc-";

    @Override
    public String description() {
        return "promote weak trailing episode → absolute_episode";
    }

    @Override
    public void process(ParseContext ctx) {
        ctx.trace.subStep("Stage 1: fill gaps between adjacent absolute_episode ranges");
        absoluteRangeFill(ctx);
        ctx.trace.subStep("Stage 2: promote bracketed episode group to absolute_episode");
        groupMarkerAbsolute(ctx);
    }

    /**
     * For each pair of adjacent {@code absolute_episode} matches where the
     * text between them is a range separator, fills in the missing intermediate
     * values as additional {@code absolute_episode} matches.
     */
    private static void absoluteRangeFill(ParseContext ctx) {
        var absEps = ctx.matches.named(MatchName.ABSOLUTE_EPISODE)
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
                fills.add(new Match(MatchName.ABSOLUTE_EPISODE, v,
                    b.start(), b.start(),
                    "", a.priority(), Set.of("range-fill"), false));
            }
        }
        for (var m : fills) ctx.matches.add(m);
    }

    private static boolean isSepRange(String gap) {
        var lower = gap.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.equals("-") || lower.equals("~") || lower.equals("to");
    }

    /**
     * For each path filepart that has no {@code SxxExx}-tagged episode, group
     * the surviving {@code episode} matches by enclosing group marker. If
     * exactly two multi-episode groups result and the gap between them is all
     * separators, rename the higher-position group to {@code absolute_episode}.
     */
    private static void groupMarkerAbsolute(ParseContext ctx) {
        for (var fp : Markers.named(ctx.markers, "path").toList()) {
            promoteWithinFilepart(ctx, fp);
        }
    }

    private static void promoteWithinFilepart(ParseContext ctx, Marker fp) {
        if (hasSxxExxEpisode(ctx, fp)) return;
        var eps = collectEpisodesInFilepart(ctx, fp);
        if (eps.isEmpty()) return;
        var groups = groupByEnclosingMarker(ctx, eps);
        var multi = groups.values().stream()
            .filter(g -> g.size() >= 2)
            .sorted(Comparator.comparingInt(g -> g.stream().mapToInt(Match::end).max().orElse(0)))
            .toList();
        if (multi.size() != 2) return;

        var lower = multi.get(0);
        var higher = multi.get(1);
        if (lower.size() != higher.size()) return;
        if (!gapBetweenGroupsIsSepOnly(ctx, lower, higher)) return;

        for (var m : higher) {
            ctx.matches.replace(m, m.withName(MatchName.ABSOLUTE_EPISODE));
        }
    }

    private static boolean hasSxxExxEpisode(ParseContext ctx, Marker fp) {
        return ctx.matches.named(MatchName.EPISODE)
            .anyMatch(m -> m.tags().contains("SxxExx")
                && m.start() >= fp.start() && m.end() <= fp.end());
    }

    private static List<Match> collectEpisodesInFilepart(ParseContext ctx, Marker fp) {
        return ctx.matches.snapshot().stream()
            .filter(m -> m.name() == MatchName.EPISODE
                && !m.isPrivate()
                && m.start() >= fp.start() && m.end() <= fp.end())
            .sorted(Comparator.comparingInt(Match::start))
            .toList();
    }

    /**
     * Group episodes by enclosing bracket/paren group marker. Episodes with no
     * enclosing marker are coalesced into a "contiguous run" group: any episode
     * adjacent (sep-only gap ≤10) to the most-recent non-enc group's last
     * member joins that group instead of starting a new one. Range-fill matches
     * always coalesce into the most-recently-opened group.
     */
    private static Map<Object, List<Match>> groupByEnclosingMarker(ParseContext ctx, List<Match> eps) {
        var groups = new LinkedHashMap<Object, List<Match>>();
        Object lastNonEncKey = null;
        for (var e : eps) {
            var enc = enclosingGroupMarker(ctx, e);
            Object key;
            if (enc != null) {
                key = enc;
            } else if (e.tags().contains("range-fill")) {
                key = groups.keySet().stream().reduce((_, b) -> b).orElse(NOENC_PREFIX + e.start());
                if (key.toString().startsWith(NOENC_PREFIX)) lastNonEncKey = key;
            } else {
                key = pickNonEncKey(ctx, e, groups, lastNonEncKey);
                lastNonEncKey = key;
            }
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(e);
        }
        return groups;
    }

    private static Marker enclosingGroupMarker(ParseContext ctx, Match m) {
        return ctx.markers.stream()
            .filter(g -> "group".equals(g.name())
                && g.start() <= m.start() && g.end() >= m.end())
            .findFirst().orElse(null);
    }

    /**
     * Pick the group key for a non-enclosed episode. Joins the previous non-enc
     * run when separated only by separators (gap ≤10); otherwise starts a new run.
     */
    private static Object pickNonEncKey(ParseContext ctx, Match e,
            Map<Object, List<Match>> groups, Object lastNonEncKey) {
        Object newKey = NOENC_PREFIX + e.start();
        if (lastNonEncKey == null) return newKey;
        var prevGroup = groups.get(lastNonEncKey);
        if (prevGroup == null || prevGroup.isEmpty()) return newKey;
        int prevEnd = prevGroup.stream().mapToInt(Match::end).max().orElse(0);
        if (prevEnd > e.start()) return newKey;
        var gap = ctx.input.substring(prevEnd, e.start());
        if (gap.length() > 10) return newKey;
        if (!gap.chars().allMatch(c -> Seps.isSep((char) c))) return newKey;
        return lastNonEncKey;
    }

    /**
     * Verify the gap between two episode groups consists only of separators.
     * Uses max end of lower and min start of higher to handle zero-width
     * range-fill matches that may appear after the real boundary.
     */
    private static boolean gapBetweenGroupsIsSepOnly(ParseContext ctx, List<Match> lower, List<Match> higher) {
        int gapStart = lower.stream().mapToInt(Match::end).max().orElse(0);
        int gapEnd = higher.stream().mapToInt(Match::start).min().orElse(ctx.input.length());
        if (gapEnd < gapStart) return false;
        var gap = ctx.input.substring(gapStart, gapEnd);
        return gap.chars().allMatch(c -> Seps.isSep((char) c));
    }
}
