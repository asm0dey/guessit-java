package io.guessit.rules.property;

import com.mirkoddd.sift.core.dsl.Connector;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.Sift.between;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Extracts weak {@code episode} candidates from bare numerics. Priority 800
 * means anything stronger wins overlap.
 *
 * <p>Three numeric shapes are scanned: 2-digit, 3-or-4-digit, and (only when
 * the input is hinted as an {@code episode}) single-digit. Single digits are
 * gated because they overlap heavily with chapter numbers, version suffixes,
 * and movie sequel numbers.
 *
 * <p>Weak matches that overlap an existing {@code SxxExx} episode are
 * skipped at extract time — the conflict solver would drop the wrong one
 * because the weak match's wider span would beat the canonical episode's
 * shorter span.
 *
 * <p>Post-pass enforces three additional removals:
 * <ul>
 * <li>If a {@code year} match exists and the input isn't episode-typed,
 * drop every weak episode (movie context).</li>
 * <li>If the input is movie-typed, drop every weak episode unconditionally.</li>
 * <li>Drop weak episodes that follow an audio_codec/source/screen_size
 * within a few separator characters — releasers don't put episode
 * numbers there; the digits are part of a codec or resolution token.</li>
 * <li>If a strong {@code SxxExx} episode survived, drop every weak episode
 * that isn't at position 0 of its filepart (those trailing weaks are
 * renamed to {@code absolute_episode} before removal).</li>
 * </ul>
 */
public final class WeakEpisodeExtractor implements Extractor {

    public static final String EPISODE = "episode";
    public static final String WEAK_EPISODE = "weak-episode";

    private static final String GRP_EP = "ep";

    private static final Set<MatchName> BLOCKING_NAMES = Set.of(
            MatchName.AUDIO_CODEC, MatchName.SCREEN_SIZE, MatchName.STREAMING_SERVICE,
            MatchName.SOURCE, MatchName.VIDEO_PROFILE, MatchName.AUDIO_CHANNELS, MatchName.AUDIO_PROFILE);

    private static final SiftPattern<Fragment> OPT_VERSION = optional().of(
            exactly(1).character('v').then().oneOrMore().digits()
    );

    private static Pattern buildPattern(Connector<Fragment> digits) {
        return Pattern.compile(
                fromAnywhere()
                        .namedCapture(capture(GRP_EP, digits))
                        .notPrecededBy(exactly(1).digits())
                        .followedBy(OPT_VERSION)
                        .notFollowedBy(exactly(1).digits())
                        .shake()
        );
    }

    private static final Pattern TWO_DIGIT = buildPattern(exactly(2).digits());
    private static final Pattern THREE_OR_FOUR = buildPattern(between(3, 4).digits());
    private static final Pattern SINGLE = buildPattern(exactly(1).digits());

    @Override
    public String name() {
        return "weak_episode";
    }

    @Override
    public int priority() {
        return 800;
    }

    @Override
    public String description() {
        return "weak trailing numeric → absolute_episode if SxxExx survives";
    }

    @Override
    public void extract(ParseContext ctx) {
        if (WeakExtractorCommon.TYPE_MOVIE.equals(ctx.options.type())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        // Pre-compute SxxExx episodes; skip weak matches that overlap them.
        var protectedEpisodes = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WeakExtractorCommon.SXXEXX))
                .toList();

        emit(ctx, input, TWO_DIGIT, seps, protectedEpisodes);
        emit(ctx, input, THREE_OR_FOUR, seps, protectedEpisodes);
        if (EPISODE.equals(ctx.options.type())) {
            emit(ctx, input, SINGLE, seps, protectedEpisodes);
        }
    }

    private void emit(ParseContext ctx, String input, Pattern p, Predicate<Match> seps,
                      List<Match> protectedEpisodes) {
        var m = p.matcher(input);
        while (m.find()) {
            int ms = m.start(GRP_EP);
            int me = m.end(GRP_EP);

            boolean isOverlapping = protectedEpisodes.stream()
                    .anyMatch(pe -> ms < pe.end() && me > pe.start());

            var head = new Match(MatchName.EPISODE, null, ms, m.end(), m.group(GRP_EP), 800, Set.of(WEAK_EPISODE), false);

            if (!isOverlapping && seps.test(head)) {
                int v = Integer.parseInt(m.group(GRP_EP));
                ctx.matches.add(new Match(MatchName.EPISODE, v, ms, me,
                        m.group(GRP_EP), 800, Set.of(WEAK_EPISODE), false));
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        if (purgeForMovieContext(ctx)) return;

        var toRemove = new ArrayList<>(weakEpisodesAdjacentToBlocking(ctx));

        var weakList = ctx.matches.named(MatchName.EPISODE).filter(m -> m.tags().contains(WEAK_EPISODE)).toList();
        var fileParts = Markers.named(ctx.markers, WeakExtractorCommon.MARKER_PATH).toList();
        var strongInFilePart = strongInFilepartPredicate(ctx, fileParts);

        if (weakList.stream().anyMatch(strongInFilePart)) {
            applyStrongEpisodeRule(ctx, weakList, fileParts, strongInFilePart, toRemove);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /**
     * RemoveWeakIfMovie analogue: when year+!episode or type=movie, purge every
     * weak (with anime/range exemptions). Returns true if purge ran (caller
     * should stop further processing).
     */
    private static boolean purgeForMovieContext(ParseContext ctx) {
        // Range-paired ≥100 weak-episodes ("Show.Name.-.476-479.(2007)...")
        // are episode ranges, not movie noise — exempt them from the
        // year-triggered movie removal so RangeFiller can expand the pair.
        boolean rangePaired = hasRangePairedWeakEpisodes(ctx);
        // Anime context: a screen_size match inside any group marker means
        // the input is an anime release. Weak-episodes carry the absolute
        // episode number, even with a year present, so don't purge.
        boolean anime = WeakExtractorCommon.hasScreenSizeInGroup(ctx);
        boolean hasYear = ctx.matches.named(MatchName.YEAR).findAny().isPresent();
        boolean episodeTyped = EPISODE.equals(ctx.options.type());
        if (!rangePaired && !anime && hasYear && !episodeTyped) {
            removeAllWeak(ctx);
            return true;
        }
        if (WeakExtractorCommon.TYPE_MOVIE.equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return true;
        }
        return false;
    }

    /**
     * Weak episodes that directly follow audio_codec/source/screen_size/etc.
     * via separator-only gap of ≤3 chars.
     */
    private static List<Match> weakEpisodesAdjacentToBlocking(ParseContext ctx) {
        var blocking = ctx.matches.all().filter(m -> BLOCKING_NAMES.contains(m.name())).toList();

        return ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE))
                .filter(weak -> blocking.stream().anyMatch(b ->
                        b.end() <= weak.start()
                                && (weak.start() - b.end()) <= 3
                                && ctx.input.substring(b.end(), weak.start()).chars().allMatch(c -> Seps.isSep((char) c))
                ))
                .toList();
    }

    /**
     * Predicate: weak match has a strong SxxExx anchor. Episode-level SxxExx
     * anchors across ALL fileparts; season-only SxxExx only within its own
     * filepart. SxxExx matches inside a title span don't anchor.
     */
    private static Predicate<Match> strongInFilepartPredicate(ParseContext ctx, List<Marker> fileParts) {
        var titleSpans = ctx.matches.named(MatchName.TITLE)
                .map(m -> new int[]{m.start(), m.end()}).toList();
        Predicate<Match> insideTitle = m -> titleSpans.stream()
                .anyMatch(t -> t[0] <= m.start() && m.end() <= t[1]);

        boolean anyEpisodeSxxExx = ctx.matches.named(MatchName.EPISODE)
                .anyMatch(m -> !m.isPrivate() && m.tags().contains(WeakExtractorCommon.SXXEXX) && !insideTitle.test(m));

        var seasonStrongSpans = ctx.matches.all()
                .filter(m -> !m.isPrivate() && m.tags().contains(WeakExtractorCommon.SXXEXX)
                        && MatchName.SEASON == m.name() && !insideTitle.test(m))
                .map(m -> new int[]{m.start(), m.end()})
                .toList();

        return weak -> hasStrongAnchor(weak, anyEpisodeSxxExx, seasonStrongSpans, fileParts);
    }

    private static boolean hasStrongAnchor(Match weak, boolean anyEpisodeSxxExx,
                                           List<int[]> seasonStrongSpans, List<Marker> fileParts) {
        if (anyEpisodeSxxExx) return true;
        for (var fp : fileParts) {
            if (weak.start() < fp.start() || weak.end() > fp.end()) continue;
            for (var sp : seasonStrongSpans) {
                if (sp[0] >= fp.start() && sp[1] <= fp.end()) return true;
            }
            return false;
        }
        return !seasonStrongSpans.isEmpty();
    }

    /**
     * Mirror Python's RemoveWeakIfSxxExx + RenameToAbsoluteEpisode +
     * EpisodeNumberSeparatorRange. For each non-leading weak under a strong
     * anchor: keep as episode if part of a contiguous low-value run; rename to
     * absolute_episode for high values; otherwise drop.
     */
    private static void applyStrongEpisodeRule(ParseContext ctx, List<Match> weakList,
                                               List<Marker> fileParts, Predicate<Match> strongInFilePart, List<Match> toRemove) {
        var allEpisodes = ctx.matches.named(MatchName.EPISODE)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        long highWeakCount = weakList.stream()
                .filter(w -> w.start() != 0 && w.value() instanceof Integer i && i >= 100)
                .count();

        for (var weak : weakList) {
            processWeakEpisode(ctx, weak, allEpisodes, fileParts, strongInFilePart,
                    highWeakCount, toRemove);
        }
    }

    private static void processWeakEpisode(ParseContext ctx, Match weak, List<Match> allEpisodes,
                                           List<Marker> fileParts, Predicate<Match> strongInFilePart,
                                           long highWeakCount, List<Match> toRemove) {
        if (!strongInFilePart.test(weak)) return;
        if (isLeadingInFilePart(weak, fileParts)) return;
        if (weak.start() == 0) return;

        int v = weak.value() instanceof Integer i ? i : -1;
        var prev = previousEpisode(allEpisodes, weak);
        var proximity = calculateProximity(ctx, weak, prev, fileParts);

        if (shouldKeepAsContiguousEpisode(v, prev, proximity, toRemove, weak)) {
            return;
        }

        if (shouldConvertToAbsoluteEpisode(v, highWeakCount, proximity.contiguous())) {
            ctx.matches.add(new Match(MatchName.ABSOLUTE_EPISODE, weak.value(), weak.start(), weak.end(),
                    weak.raw(), weak.priority(), weak.tags(), weak.isPrivate()));
        }
        toRemove.add(weak);
    }

    private static ProximityInfo calculateProximity(ParseContext ctx, Match weak,
                                                    Match prev, List<Marker> fileParts) {
        if (prev == null) {
            return new ProximityInfo(false, false);
        }

        String gap = ctx.input.substring(prev.end(), weak.start());
        boolean contiguous = gap.chars().allMatch(c -> Seps.isSep((char) c));
        boolean sameFilePart = inSameFilePart(prev, weak, fileParts);

        return new ProximityInfo(contiguous, sameFilePart);
    }

    private static boolean shouldKeepAsContiguousEpisode(int v, Match prev,
                                                         ProximityInfo proximity, List<Match> toRemove, Match weak) {
        if (!proximity.contiguous() || !proximity.sameFilePart() || v >= 100) {
            return false;
        }

        int prevVal = prev != null && prev.value() instanceof Integer pi ? pi : -1;
        if (prevVal > 0 && (v - prevVal) > 5) {
            toRemove.add(weak);
        }
        return true;
    }

    private static boolean shouldConvertToAbsoluteEpisode(int v, long highWeakCount,
                                                          boolean contiguous) {
        return v >= 100 && (highWeakCount >= 2 || !contiguous);
    }

    private record ProximityInfo(boolean contiguous, boolean sameFilePart) {
    }

    private static boolean isLeadingInFilePart(Match weak, List<Marker> fileParts) {
        for (var fp : fileParts) {
            if (weak.start() == fp.start() && weak.end() <= fp.end()) return true;
        }
        return false;
    }

    private static Match previousEpisode(List<Match> allEpisodes, Match weak) {
        return allEpisodes.stream()
                .filter(ep -> ep != weak && ep.end() <= weak.start())
                .reduce((_, second) -> second)
                .orElse(null);
    }

    private static boolean inSameFilePart(Match a, Match b, List<Marker> fileParts) {
        for (var fp : fileParts) {
            if (WeakExtractorCommon.isInside(a, fp) && WeakExtractorCommon.isInside(b, fp)) return true;
        }
        return false;
    }

    private static boolean hasRangePairedWeakEpisodes(ParseContext ctx) {
        var weakList = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WeakExtractorCommon.WEAK_DUPLICATE))
                .filter(m -> m.value() instanceof Integer i && i >= 100)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        return IntStream.range(0, weakList.size() - 1).anyMatch(i -> {
            var a = weakList.get(i);
            var b = weakList.get(i + 1);

            int va = (Integer) a.value();
            int vb = (Integer) b.value();
            int gapLen = b.start() - a.end();

            return vb > va
                    && gapLen > 0
                    && gapLen <= 5
                    && WeakExtractorCommon.RANGE_SEP.matcher(ctx.input.substring(a.end(), b.start())).matches();
        });
    }

    private static void removeAllWeak(ParseContext ctx) {
        var weakList = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE))
                .filter(m -> !(m.tags().contains(WeakExtractorCommon.WEAK_DUPLICATE) && !inAnyGroupMarker(ctx, m)))
                .toList();
        var weakSeasons = ctx.matches.named(MatchName.SEASON)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && m.tags().contains(WeakExtractorCommon.WEAK_DUPLICATE))
                .filter(m -> inAnyGroupMarker(ctx, m))
                .toList();
        for (var m : weakList) ctx.matches.remove(m);
        for (var m : weakSeasons) ctx.matches.remove(m);
    }

    private static boolean inAnyGroupMarker(ParseContext ctx, Match m) {
        return ctx.markers.stream()
                .anyMatch(mk -> WeakExtractorCommon.MARKER_GROUP.equals(mk.name())
                        && WeakExtractorCommon.isInside(m, mk));
    }
}