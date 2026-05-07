package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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
 *   <li>If a {@code year} match exists and the input isn't episode-typed,
 *       drop every weak episode (movie context).</li>
 *   <li>If the input is movie-typed, drop every weak episode unconditionally.</li>
 *   <li>Drop weak episodes that follow an audio_codec/source/screen_size
 *       within a few separator characters — releasers don't put episode
 *       numbers there; the digits are part of a codec or resolution token.</li>
 *   <li>If a strong {@code SxxExx} episode survived, drop every weak episode
 *       that isn't at position 0 of its filepart (those trailing weaks are
 *       renamed to {@code absolute_episode} before removal).</li>
 * </ul>
 */
public final class WeakEpisodeExtractor implements Extractor {
    private static final Pattern TWO_DIGIT = Pattern.compile("(?<!\\d)(\\d{2})(?:v\\d+)?(?!\\d)");
    private static final Pattern THREE_OR_FOUR = Pattern.compile("(?<!\\d)(\\d{3,4})(?:v\\d+)?(?!\\d)");
    private static final Pattern SINGLE = Pattern.compile("(?<!\\d)(\\d)(?:v\\d+)?(?!\\d)");
    private static final Pattern RANGE_SEP = Pattern.compile("[ ._]*[-~][ ._]*");
    public static final String EPISODE = "episode";
    public static final String WEAK_EPISODE = "weak-episode";
    private static final String WEAK_DUPLICATE = "weak-duplicate";
    private static final String SXXEXX = "SxxExx";
    private static final String SEASON = "season";
    private static final String GROUP = "group";
    private static final String PATH = "path";
    private static final Set<MatchName> BLOCKING_NAMES = Set.of(
            MatchName.AUDIO_CODEC, MatchName.SCREEN_SIZE, MatchName.STREAMING_SERVICE,
            MatchName.SOURCE, MatchName.VIDEO_PROFILE, MatchName.AUDIO_CHANNELS, MatchName.AUDIO_PROFILE);

    @Override public String name() { return "weak_episode"; }
    @Override public int priority() { return 800; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        // Pre-compute SxxExx episodes; skip weak matches that overlap them.
        var protectedEpisodes = ctx.matches.named(MatchName.EPISODE)
            .filter(m -> m.tags().contains(SXXEXX))
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
            int ms = m.start(1);
            int me = m.end(1);
            int validateEnd = m.end();
            if (overlapsAnyProtected(ms, me, protectedEpisodes)) continue;

            var head = new Match(MatchName.EPISODE, null, ms, validateEnd, m.group(1), 800, Set.of(WEAK_EPISODE), false);
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            ctx.matches.add(new Match(MatchName.EPISODE, v, ms, me,
                    m.group(1), 800, Set.of(WEAK_EPISODE), false));
        }
    }

    private boolean overlapsAnyProtected(int start, int end, List<Match> protectedEpisodes) {
        for (var pe : protectedEpisodes) {
            if (start < pe.end() && end > pe.start()) {
                return true;
            }
        }
        return false;
    }

    /** Replicates RemoveWeakIfMovie + RemoveWeak (drop weak-episode after audio/video/source). */
    @Override
    public void postProcess(ParseContext ctx) {
        if (purgeForMovieContext(ctx)) return;

        var toRemove = new ArrayList<>(weakEpisodesAdjacentToBlocking(ctx));

        var weaks = ctx.matches.named(MatchName.EPISODE).filter(m -> m.tags().contains(WEAK_EPISODE)).toList();
        var fileparts = Markers.named(ctx.markers, PATH).toList();
        var strongInFilepart = strongInFilepartPredicate(ctx, fileparts);

        if (weaks.stream().anyMatch(strongInFilepart)) {
            applyStrongEpisodeRule(ctx, weaks, fileparts, strongInFilepart, toRemove);
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
        boolean anime = hasScreenSizeInGroup(ctx);
        boolean hasYear = ctx.matches.named(MatchName.YEAR).findAny().isPresent();
        boolean episodeTyped = EPISODE.equals(ctx.options.type());
        if (!rangePaired && !anime && hasYear && !episodeTyped) {
            removeAllWeak(ctx);
            return true;
        }
        if ("movie".equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return true;
        }
        return false;
    }

    /** Weak episodes that directly follow audio_codec/source/screen_size/etc.
     *  via separator-only gap of ≤3 chars. */
    private static List<Match> weakEpisodesAdjacentToBlocking(ParseContext ctx) {
        var blocking = ctx.matches.all().filter(m -> BLOCKING_NAMES.contains(m.name())).toList();
        var weaks = ctx.matches.named(MatchName.EPISODE).filter(m -> m.tags().contains(WEAK_EPISODE)).toList();
        var drop = new ArrayList<Match>();
        for (var weak : weaks) {
            for (var b : blocking) {
                if (b.end() > weak.start() || weak.start() - b.end() > 3) continue;
                String gap = ctx.input.substring(b.end(), weak.start());
                if (gap.chars().allMatch(c -> Seps.isSep((char) c))) {
                    drop.add(weak);
                    break;
                }
            }
        }
        return drop;
    }

    /**
     * Predicate: weak match has a strong SxxExx anchor. Episode-level SxxExx
     * anchors across ALL fileparts; season-only SxxExx only within its own
     * filepart. SxxExx matches inside a title span don't anchor.
     */
    private static Predicate<Match> strongInFilepartPredicate(ParseContext ctx, List<Marker> fileparts) {
        var titleSpans = ctx.matches.named(MatchName.TITLE)
            .map(m -> new int[]{m.start(), m.end()}).toList();
        Predicate<Match> insideTitle = m -> titleSpans.stream()
            .anyMatch(t -> t[0] <= m.start() && m.end() <= t[1]);

        boolean anyEpisodeSxxExx = ctx.matches.named(MatchName.EPISODE)
            .anyMatch(m -> !m.isPrivate() && m.tags().contains(SXXEXX) && !insideTitle.test(m));

        var seasonStrongSpans = ctx.matches.all()
            .filter(m -> !m.isPrivate() && m.tags().contains(SXXEXX)
                && MatchName.SEASON == m.name() && !insideTitle.test(m))
            .map(m -> new int[]{m.start(), m.end()})
            .toList();

        return weak -> hasStrongAnchor(weak, anyEpisodeSxxExx, seasonStrongSpans, fileparts);
    }

    private static boolean hasStrongAnchor(Match weak, boolean anyEpisodeSxxExx,
                                           List<int[]> seasonStrongSpans, List<Marker> fileparts) {
        if (anyEpisodeSxxExx) return true;
        for (var fp : fileparts) {
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
    private static void applyStrongEpisodeRule(ParseContext ctx, List<Match> weaks,
                                               List<Marker> fileparts, Predicate<Match> strongInFilepart, List<Match> toRemove) {
        var allEpisodes = ctx.matches.named(MatchName.EPISODE)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        long highWeakCount = weaks.stream()
                .filter(w -> w.start() != 0 && w.value() instanceof Integer i && i >= 100)
                .count();

        for (var weak : weaks) {
            processWeakEpisode(ctx, weak, allEpisodes, fileparts, strongInFilepart,
                    highWeakCount, toRemove);
        }
    }

    private static void processWeakEpisode(ParseContext ctx, Match weak, List<Match> allEpisodes,
                                           List<Marker> fileparts, Predicate<Match> strongInFilepart,
                                           long highWeakCount, List<Match> toRemove) {
        if (!strongInFilepart.test(weak)) return;
        if (isLeadingInFilepart(weak, fileparts)) return;
        if (weak.start() == 0) return;

        int v = weak.value() instanceof Integer i ? i : -1;
        var prev = previousEpisode(allEpisodes, weak);
        var proximity = calculateProximity(ctx, weak, prev, fileparts);

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
                                                    Match prev, List<Marker> fileparts) {
        if (prev == null) {
            return new ProximityInfo(false, false);
        }

        String gap = ctx.input.substring(prev.end(), weak.start());
        boolean contiguous = gap.chars().allMatch(c -> Seps.isSep((char) c));
        boolean sameFilepart = inSameFilepart(prev, weak, fileparts);

        return new ProximityInfo(contiguous, sameFilepart);
    }

    private static boolean shouldKeepAsContiguousEpisode(int v, Match prev,
                                                         ProximityInfo proximity, List<Match> toRemove, Match weak) {
        if (!proximity.contiguous() || !proximity.sameFilepart() || v >= 100) {
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
    
    private record ProximityInfo(boolean contiguous, boolean sameFilepart) {}

    private static boolean isLeadingInFilepart(Match weak, List<Marker> fileparts) {
        for (var fp : fileparts) {
            if (weak.start() == fp.start() && weak.end() <= fp.end()) return true;
        }
        return false;
    }

    private static Match previousEpisode(List<Match> allEpisodes, Match weak) {
        Match prev = null;
        for (var ep : allEpisodes) {
            if (ep == weak) continue;
            if (ep.end() <= weak.start()) prev = ep;
            else break;
        }
        return prev;
    }

    private static boolean inSameFilepart(Match a, Match b, List<Marker> fileparts) {
        for (var fp : fileparts) {
            if (a.start() >= fp.start() && a.end() <= fp.end()
                && b.start() >= fp.start() && b.end() <= fp.end()) return true;
        }
        return false;
    }

    /**
     * True when any group marker contains a screen_size match — an anime-release
     * signal. Shared with {@link WeakDuplicateExtractor}.
     */
    static boolean hasScreenSizeInGroup(ParseContext ctx) {
        for (var mk : ctx.markers) {
            if (!GROUP.equals(mk.name())) continue;
            if (ctx.matches.named(MatchName.SCREEN_SIZE)
                    .anyMatch(m -> m.start() >= mk.start() && m.end() <= mk.end())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRangePairedWeakEpisodes(ParseContext ctx) {
        var weaks = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.value() instanceof Integer i && i >= 100)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        for (int i = 0; i + 1 < weaks.size(); i++) {
            var a = weaks.get(i);
            var b = weaks.get(i + 1);
            if (!(a.value() instanceof Integer va) || !(b.value() instanceof Integer vb)) continue;
            if (vb <= va) continue;
            int gapLen = b.start() - a.end();
            if (gapLen <= 0 || gapLen > 5) continue;
            if (RANGE_SEP.matcher(ctx.input.substring(a.end(), b.start())).matches()) return true;
        }
        return false;
    }

    private static void removeAllWeak(ParseContext ctx) {
        // Mirror python RemoveWeakIfMovie: weak-episode tagged matches are
        // removed when year is present and type isn't episode. Python's
        // weak_duplicate matches ALSO carry the weak-episode tag and are
        // therefore removed too. Java previously excluded weak-duplicate to
        // protect compact "401"-style SSEE pairs, but for inputs where the
        // pair lives in a non-title context (e.g. "Aac-128(...)" inside a
        // group bracket) it produces phantom season=1 episode=28. Only keep
        // weak-duplicate exclusion when the pair sits OUTSIDE any group
        // marker — those are the canonical SSEE shapes.
        var weaks = ctx.matches.named(MatchName.EPISODE)
            .filter(m -> m.tags().contains(WEAK_EPISODE))
            .filter(m -> !(m.tags().contains(WEAK_DUPLICATE) && !inAnyGroupMarker(ctx, m)))
            .toList();
        var weakSeasons = ctx.matches.named(MatchName.SEASON)
            .filter(m -> m.tags().contains(WEAK_EPISODE) && m.tags().contains(WEAK_DUPLICATE))
            .filter(m -> inAnyGroupMarker(ctx, m))
            .toList();
        for (var m : weaks) ctx.matches.remove(m);
        for (var m : weakSeasons) ctx.matches.remove(m);
    }

    private static boolean inAnyGroupMarker(ParseContext ctx, Match m) {
        return ctx.markers.stream()
            .anyMatch(mk -> GROUP.equals(mk.name())
                && mk.start() <= m.start() && mk.end() >= m.end());
    }
}
