package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.*;

/**
 * PostPhase processor that finalises {@code absolute_episode} promotion after
 * {@link EpisodeNumberSeparatorRange} and {@link RangeFiller} have expanded all
 * bare episode ranges.
 */
public final class AbsoluteEpisodePromoter implements PostPhase.PostProcessor {

    private static final int MAX_ABS_RANGE = 20;
    private static final int MAX_NON_ENC_GAP = 10;

    private static final String NOENC_PREFIX = "noenc-";
    private static final String TAG_RANGE_FILL = "range-fill";
    private static final String TAG_SXX_EXX = "SxxExx";

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
        for (int i = 0; i < absEps.size() - 1; i++) {
            fills.addAll(generateFillsIfValid(ctx, absEps.get(i), absEps.get(i + 1)));
        }

        fills.forEach(ctx.matches::add);
    }

    private static List<Match> generateFillsIfValid(ParseContext ctx, Match a, Match b) {
        if (!(a.value() instanceof Integer va) || !(b.value() instanceof Integer vb)) {
            return List.of();
        }

        if (!isFillableGap(ctx.input, a, b, va, vb)) {
            return List.of();
        }

        return createMissingMatches(a, b, va, vb);
    }

    private static boolean isFillableGap(String input, Match a, Match b, int va, int vb) {
        if (vb <= va + 1) return false;
        if (vb - va - 1 > MAX_ABS_RANGE) return false;
        if (b.start() <= a.end()) return false;

        var gapText = input.substring(a.end(), b.start());
        return isSepRange(gapText);
    }

    private static List<Match> createMissingMatches(Match a, Match b, int va, int vb) {
        var fills = new ArrayList<Match>();
        for (int v = va + 1; v < vb; v++) {
            fills.add(new Match(
                    MatchName.ABSOLUTE_EPISODE,
                    v,
                    b.start(),
                    b.start(),
                    "",
                    a.priority(),
                    Set.of(TAG_RANGE_FILL),
                    false
            ));
        }
        return fills;
    }

    private static boolean isSepRange(String gap) {
        var lower = gap.strip().toLowerCase(Locale.ROOT);
        return lower.equals("-") || lower.equals("~") || lower.equals("to");
    }

    private static void groupMarkerAbsolute(ParseContext ctx) {
        Markers.named(ctx.markers, "path").forEach(fp -> promoteWithinFilePart(ctx, fp));
    }

    private static void promoteWithinFilePart(ParseContext ctx, Marker fp) {
        if (hasSxxExxEpisode(ctx, fp)) return;

        var eps = collectEpisodesInFilePart(ctx, fp);
        if (eps.isEmpty()) return;

        var multiEpisodeGroups = extractMultiEpisodeGroups(ctx, eps);
        if (multiEpisodeGroups.size() != 2) return;

        var lower = multiEpisodeGroups.get(0);
        var higher = multiEpisodeGroups.get(1);

        if (lower.size() != higher.size() || !gapBetweenGroupsIsSepOnly(ctx, lower, higher)) return;

        higher.forEach(m -> ctx.matches.replace(m, m.withName(MatchName.ABSOLUTE_EPISODE)));
    }

    private static boolean hasSxxExxEpisode(ParseContext ctx, Marker fp) {
        return ctx.matches.named(MatchName.EPISODE)
                .anyMatch(m -> m.tags().contains(TAG_SXX_EXX) && m.start() >= fp.start() && m.end() <= fp.end());
    }

    private static List<Match> collectEpisodesInFilePart(ParseContext ctx, Marker fp) {
        return ctx.matches.snapshot().stream()
                .filter(m -> m.name() == MatchName.EPISODE && !m.isPrivate())
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
    }

    private static List<List<Match>> extractMultiEpisodeGroups(ParseContext ctx, List<Match> eps) {
        var clusters = new LinkedHashMap<Object, List<Match>>();

        Object lastKey = null;
        Object lastNonEncKey = null;

        for (var e : eps) {
            Object currentKey;
            var enc = enclosingGroupMarker(ctx, e);

            if (enc != null) {
                currentKey = enc;
            } else if (e.tags().contains(TAG_RANGE_FILL) && lastKey != null) {
                currentKey = lastKey;
            } else {
                currentKey = resolveNonEncKey(ctx, e, clusters, lastNonEncKey);
                lastNonEncKey = currentKey;
            }

            clusters.computeIfAbsent(currentKey, _ -> new ArrayList<>()).add(e);
            lastKey = currentKey;
        }

        return clusters.values().stream()
                .filter(g -> g.size() >= 2)
                .sorted(Comparator.comparingInt(g -> g.stream().mapToInt(Match::end).max().orElse(0)))
                .toList();
    }

    private static Marker enclosingGroupMarker(ParseContext ctx, Match m) {
        return ctx.markers.stream()
                .filter(g -> "group".equals(g.name()) && g.start() <= m.start() && g.end() >= m.end())
                .findFirst()
                .orElse(null);
    }

    private static Object resolveNonEncKey(ParseContext ctx, Match e, Map<Object, List<Match>> clusters, Object lastNonEncKey) {
        Object newKey = NOENC_PREFIX + e.start();
        if (lastNonEncKey == null) return newKey;

        var prevGroup = clusters.get(lastNonEncKey);
        if (prevGroup == null || prevGroup.isEmpty()) return newKey;

        int prevEnd = prevGroup.stream().mapToInt(Match::end).max().orElse(0);
        if (prevEnd > e.start()) return newKey;

        var gap = ctx.input.substring(prevEnd, e.start());
        if (gap.length() > MAX_NON_ENC_GAP || !isSepOnly(gap)) return newKey;

        return lastNonEncKey;
    }

    private static boolean gapBetweenGroupsIsSepOnly(ParseContext ctx, List<Match> lower, List<Match> higher) {
        int gapStart = lower.stream().mapToInt(Match::end).max().orElse(0);
        int gapEnd = higher.stream().mapToInt(Match::start).min().orElse(ctx.input.length());

        return gapEnd >= gapStart && isSepOnly(ctx.input.substring(gapStart, gapEnd));
    }

    private static boolean isSepOnly(String text) {
        return text.chars().allMatch(c -> Seps.isSep((char) c));
    }
}