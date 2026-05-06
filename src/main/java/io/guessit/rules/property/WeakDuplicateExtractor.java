package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.engine.MatchName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises 3-4 digit runs that could plausibly be a {@code SSEE} compact
 * season+episode (e.g. "0102" → S01E02). Emitted at priority 700 with the
 * {@code weak-duplicate} + {@code coexist} tags so they don't fight stronger
 * matches in the conflict solver.
 *
 * <p>The post-pass kills these candidates whenever a real {@code SxxExx} or
 * word-form season/episode exists; the compact form is only useful when no
 * canonical form is present.
 *
 * <p>Skipped entirely when the user hinted {@code movie} or set
 * {@code episode_prefer_number}, since both indicate the digits are not a
 * compact season+episode.
 */
public final class WeakDuplicateExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})(\\d{2})(?!\\d)");
    private static final Pattern RANGE_SEP = Pattern.compile("[ ._]*[-~][ ._]*");
    public static final String WEAK_DUPLICATE = "weak-duplicate";
    public static final MatchName SEASON = MatchName.SEASON;
    public static final MatchName EPISODE = MatchName.EPISODE;
    public static final String WEAK_EPISODE = "weak-episode";
    private static final String COEXIST = "coexist";
    private static final String SXXEXX = "SxxExx";
    private static final String EPISODE_WORD = "episode-word";
    private static final String SEASON_WORD = "season-word";
    private static final String EXPECTED = "expected";

    @Override
    public String name() {
        return "weak_duplicate";
    }

    @Override
    public int priority() {
        return 700;
    }

    /** True if any non-separator character follows {@code pos} in {@code input}. */
    private static boolean hasContentAfterPos(String input, int pos) {
        for (int i = pos; i < input.length(); i++) {
            if (!Seps.isSep(input.charAt(i))) return true;
        }
        return false;
    }

    /** Check if position {@code pos} is preceded by "...non-sep [sep] - [sep]" — a
     *  dash-then-digits anime-style separator. */
    private static boolean isDashSeparatedBefore(String input, int pos) {
        int i = pos - 1;
        if (i < 0 || !Seps.isSep(input.charAt(i))) return false;
        i--;
        if (i < 0 || input.charAt(i) != '-') return false;
        i--;
        if (i >= 0 && Seps.isSep(input.charAt(i))) i--;
        return i >= 0 && !Seps.isSep(input.charAt(i)) && input.charAt(i) != '-';
    }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        if (Boolean.TRUE.equals(ctx.options.episodePreferNumber())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            var span = new Match(MatchName.WEAK, null, m.start(), m.end(), m.group(), 700, Set.of(), false);
            if (!seps.test(span)) continue;
            int s = Integer.parseInt(m.group(1));
            int e = Integer.parseInt(m.group(2));
            ctx.matches.add(new Match(MatchName.SEASON, s, m.start(1), m.end(1),
                    m.group(1), 700, Set.of(WEAK_EPISODE, WEAK_DUPLICATE, COEXIST), false));
            ctx.matches.add(new Match(MatchName.EPISODE, e, m.start(2), m.end(2),
                    m.group(2), 700, Set.of(WEAK_EPISODE, WEAK_DUPLICATE, COEXIST), false));
        }
    }

    /**
     * Replicates RemoveWeakDuplicate: drop the weak-duplicate pair when a strong SxxExx exists.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var fileparts = Markers.named(ctx.markers, "path").toList();
        boolean hasSxxExx = ctx.matches.tagged(SXXEXX).findAny().isPresent();

        dropInAnimeContext(ctx);
        if (!hasSxxExx) dropAnimeDashSeparatedPair(ctx);
        if (!hasSxxExx) dropDuplicateInsideRangePair(ctx);
        if (!hasSxxExx) mergeLeadingWeakWithDuplicate(ctx);
        dropWeakIfMoviePerFilepart(ctx);
        dropInsideExpectedTitle(ctx, false);
        dropOverlappingStrongerProperty(ctx);
        dropAllWeakEpisodeWhenDuplicate(ctx, fileparts);
        dedupKeepLastPerFilepart(ctx, fileparts);
        dropInsideExpectedTitle(ctx, true);
        dropDuplicateWhenStrongInFilepart(ctx, fileparts);
    }

    /**
     * Anime-context: leading [Group]/(Group) bracket OR screen_size inside a group
     * marker means the 3-4 digit run is an absolute episode, not compact SSEE.
     * Drop weak-duplicates entirely.
     */
    private static void dropInAnimeContext(ParseContext ctx) {
        // Anime-context is only valid when the leading bracket contains a
        // non-numeric group name (e.g. "[Fansub]"). If the bracket contains
        // only digits (e.g. "[401]"), it IS the compact SSEE — do not drop.
        boolean animeContext = !ctx.input.isEmpty()
                && (ctx.input.charAt(0) == '[' || ctx.input.charAt(0) == '(')
                && ctx.markers.stream().anyMatch(mk -> "group".equals(mk.name()) && mk.start() <= 1
                        && !mk.raw().matches("\\d+"));
        if (!animeContext) animeContext = WeakEpisodeExtractor.hasScreenSizeInGroup(ctx);
        if (!animeContext) return;
        var dropDup = ctx.matches.tagged(WEAK_DUPLICATE).toList();
        for (var m : dropDup) ctx.matches.remove(m);
    }

    /**
     * Anime non-bracket: NNN preceded by "[sep]-[sep]" with anime decoration
     * trailing ([HD], (year), or another weak-episode forming a range) and no
     * SxxExx → drop the SSEE pair so wider weak-episode survives.
     */
    private static void dropAnimeDashSeparatedPair(ParseContext ctx) {
        var seasonRuns = ctx.matches.named(MatchName.SEASON)
                .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> isDashSeparatedBefore(ctx.input, m.start()))
                .toList();
        for (var seasonMatch : seasonRuns) {
            var matchingEpisode = ctx.matches.named(MatchName.EPISODE)
                    .filter(em -> em.tags().contains(WEAK_DUPLICATE) && em.start() == seasonMatch.end())
                    .findFirst().orElse(null);
            if (matchingEpisode == null) continue;
            int runEnd = matchingEpisode.end();
            if (!hasContentAfterPos(ctx.input, runEnd)) continue;
            int sStart = seasonMatch.start();
            if (!hasRangePartner(ctx, runEnd, sStart)) continue;
            ctx.matches.remove(seasonMatch);
            ctx.matches.remove(matchingEpisode);
        }
    }

    /** True if a weak-episode (≥3 chars / not weak-duplicate) sits within a 1-5 char
     *  range-separator gap right after {@code runEnd} or right before {@code sStart}. */
    private static boolean hasRangePartner(ParseContext ctx, int runEnd, int sStart) {
        boolean trailing = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.start() >= runEnd)
                .anyMatch(m -> isRangeGap(ctx.input.substring(runEnd, m.start())));
        boolean leading = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.end() <= sStart)
                .anyMatch(m -> isRangeGap(ctx.input.substring(m.end(), sStart)));
        return trailing || leading;
    }

    private static boolean isRangeGap(String gap) {
        return !gap.isEmpty() && gap.length() <= 5 && RANGE_SEP.matcher(gap).matches();
    }

    /**
     * Adjacent dash-separated weak-episode pairs (e.g. "Bleach - 313-314"): both
     * numbers are absolute episodes. Drop weak-duplicate matches between them.
     */
    private static void dropDuplicateInsideRangePair(ParseContext ctx) {
        var weakEpisodes = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.raw() != null && m.raw().length() >= 3)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        var dropDup = new ArrayList<Match>();
        for (int i = 0; i + 1 < weakEpisodes.size(); i++) {
            var a = weakEpisodes.get(i);
            var b = weakEpisodes.get(i + 1);
            if (!(a.value() instanceof Integer va) || !(b.value() instanceof Integer vb)) continue;
            if (vb <= va) continue;
            String gap = ctx.input.substring(a.end(), b.start());
            if (!isRangeGap(gap)) continue;
            ctx.matches.tagged(WEAK_DUPLICATE)
                    .filter(m -> m.start() >= a.start() && m.end() <= b.end())
                    .forEach(dropDup::add);
        }
        for (var m : dropDup) ctx.matches.remove(m);
    }

    /**
     * "weak-episode (≥100) - weak-duplicate-pair" form: leading weak-episode
     * sits dash-separated before a duplicate pair. Drop the pair and re-add the
     * combined digits as a plain weak-episode.
     */
    private static void mergeLeadingWeakWithDuplicate(ParseContext ctx) {
        var leadingWeak = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                .filter(m -> m.value() instanceof Integer i && i >= 100)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        var dupSeasons = ctx.matches.named(MatchName.SEASON)
                .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
        var toRemove = new ArrayList<Match>();
        var toAdd = new ArrayList<Match>();
        for (var lead : leadingWeak) {
            for (var dupS : dupSeasons) {
                if (dupS.start() < lead.end()) continue;
                String gap = ctx.input.substring(lead.end(), dupS.start());
                if (!isRangeGap(gap)) continue;
                var dupE = ctx.matches.named(MatchName.EPISODE)
                        .filter(em -> em.tags().contains(WEAK_DUPLICATE) && em.start() == dupS.end())
                        .findFirst().orElse(null);
                if (dupE == null) continue;
                int spanStart = dupS.start();
                int spanEnd = dupE.end();
                int combined = Integer.parseInt(ctx.input.substring(spanStart, spanEnd));
                toRemove.add(dupS);
                toRemove.add(dupE);
                toAdd.add(new Match(MatchName.EPISODE, combined, spanStart, spanEnd,
                        ctx.input.substring(spanStart, spanEnd), 800,
                        Set.of(WEAK_EPISODE), false));
                break;
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
        for (var m : toAdd) ctx.matches.add(m);
    }

    /**
     * RemoveWeakIfMovie: per-filepart, when type != episode and a year exists,
     * drop weak-duplicate matches in that filepart UNLESS they form the pair
     * immediately following the year (separator-only gap).
     */
    private static void dropWeakIfMoviePerFilepart(ParseContext ctx) {
        if ("episode".equals(ctx.options.type())) return;
        var fileparts = Markers.named(ctx.markers, "path").toList();
        var dropForMovie = new ArrayList<Match>();
        for (var fp : fileparts) {
            var year = ctx.matches.named(MatchName.YEAR)
                    .filter(y -> y.start() >= fp.start() && y.end() <= fp.end())
                    .findFirst().orElse(null);
            if (year == null) continue;
            int yearEnd = year.end();
            var pairStarts = collectWeakDupPairStartsAfterYear(ctx, fp, yearEnd);
            ctx.matches.tagged(WEAK_DUPLICATE)
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .filter(m -> !isExemptFromMovieDrop(ctx, m, pairStarts))
                    .forEach(dropForMovie::add);
        }
        for (var m : dropForMovie) ctx.matches.remove(m);
    }

    /** Season-side starts of weak-duplicate pairs whose season match begins
     *  after {@code yearEnd} via separator-only gap, restricted to the filepart. */
    private static Set<Integer> collectWeakDupPairStartsAfterYear(ParseContext ctx, Marker fp, int yearEnd) {
        var pairStarts = new HashSet<Integer>();
        ctx.matches.named(MatchName.SEASON)
                .filter(s -> s.tags().contains(WEAK_DUPLICATE))
                .filter(s -> s.start() >= fp.start() && s.end() <= fp.end())
                .filter(s -> s.start() >= yearEnd)
                .filter(s -> ctx.input.substring(yearEnd, s.start())
                        .chars().allMatch(c -> Seps.isSep((char) c)))
                .forEach(s -> pairStarts.add(s.start()));
        return pairStarts;
    }

    /** Exempt the pair members (season + adjacent episode) from movie-drop. */
    private static boolean isExemptFromMovieDrop(ParseContext ctx, Match m, Set<Integer> pairStarts) {
        if (pairStarts.contains(m.start())) return true;
        // Episode of pair starts at season.end. Season raw is 1-2 chars, so
        // episode start is ps+1 or ps+2.
        for (int ps : pairStarts) {
            if (m.start() != ps + 1 && m.start() != ps + 2) continue;
            var seasonMatch = ctx.matches.named(MatchName.SEASON)
                    .filter(s -> s.start() == ps && s.tags().contains(WEAK_DUPLICATE))
                    .findFirst().orElse(null);
            if (seasonMatch != null && m.start() == seasonMatch.end()) return true;
        }
        return false;
    }

    /**
     * Drop weak-duplicate (and optionally weak-episode) matches inside any
     * expected-title span. The user told us those digits are part of the title.
     */
    private static void dropInsideExpectedTitle(ParseContext ctx, boolean includeWeakEpisode) {
        var expectedTitles = ctx.matches.named(MatchName.TITLE)
                .filter(m -> m.tags().contains(EXPECTED))
                .toList();
        if (expectedTitles.isEmpty()) return;
        var drop = ctx.matches.all()
                .filter(m -> MatchName.SEASON == m.name() || MatchName.EPISODE == m.name())
                .filter(m -> m.tags().contains(WEAK_DUPLICATE)
                        || (includeWeakEpisode && m.tags().contains(WEAK_EPISODE)))
                .filter(m -> expectedTitles.stream().anyMatch(t -> t.start() <= m.start() && m.end() <= t.end()))
                .toList();
        for (var m : drop) ctx.matches.remove(m);
    }

    /**
     * Pre-clean weak-duplicate matches that overlap a stronger property
     * (year, date, video_codec, audio_codec, screen_size, certain "other"
     * spans, audio/video bitrate). Mirrors python's default longest-wins
     * conflict solver running before WeakConflictSolver/RemoveWeakDuplicate.
     */
    private static void dropOverlappingStrongerProperty(ParseContext ctx) {
        var years = ctx.matches.named(MatchName.YEAR).toList();
        var dates = ctx.matches.named(MatchName.DATE).toList();
        var codecs = ctx.matches.all()
                .filter(x -> x.name() == MatchName.VIDEO_CODEC || x.name() == MatchName.AUDIO_CODEC)
                .toList();
        var screens = ctx.matches.named(MatchName.SCREEN_SIZE).toList();
        // Restrict "other" overlaps to multi-token raws containing digits
        // (e.g. "BT.2020") to avoid blanket-overlap of generic single-word others.
        var others = ctx.matches.named(MatchName.OTHER)
                .filter(m -> m.raw() != null && m.raw().length() >= 4
                        && m.raw().chars().anyMatch(Character::isDigit))
                .toList();
        var bitRates = ctx.matches.all()
                .filter(m -> m.name() == MatchName.AUDIO_BIT_RATE || m.name() == MatchName.VIDEO_BIT_RATE)
                .toList();
        @SuppressWarnings("unchecked")
        List<Match>[] groups = new List[]{years, dates, codecs, screens, others, bitRates};
        var preClean = new ArrayList<Match>();
        for (var name : new MatchName[]{MatchName.SEASON, MatchName.EPISODE}) {
            for (var m : ctx.matches.named(name).toList()) {
                if (!m.tags().contains(WEAK_DUPLICATE)) continue;
                for (var group : groups) {
                    if (group.stream().anyMatch(o -> o.overlaps(m))) {
                        preClean.add(m);
                        break;
                    }
                }
            }
        }
        for (var m : preClean) ctx.matches.remove(m);
    }

    /**
     * Per-filepart: when weak-duplicate exists and no SxxExx in the same
     * filepart, drop ALL weak-episode in that filepart. Mirrors python
     * WeakConflictSolver's "elif weak_dup_matches" branch.
     */
    private static void dropAllWeakEpisodeWhenDuplicate(ParseContext ctx, List<Marker> fileparts) {
        for (var fp : fileparts) {
            boolean hasDup = ctx.matches.tagged(WEAK_DUPLICATE)
                    .anyMatch(m -> m.start() >= fp.start() && m.end() <= fp.end());
            if (!hasDup) continue;
            if (filepartHasStrongMarker(ctx, fp)) continue;
            var dropWeakEp = ctx.matches.tagged(WEAK_EPISODE)
                    .filter(m -> !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .toList();
            for (var m : dropWeakEp) ctx.matches.remove(m);
        }
    }

    /**
     * Per-filepart: dedup weak-duplicate matches by name keeping only the LAST
     * occurrence. Each pair contributes one season + one episode; dedup them
     * independently so both sides of the surviving pair stay.
     * Example: "the.100.109" → reverse-iter keeps 1+09, drops 1+00.
     */
    private static void dedupKeepLastPerFilepart(ParseContext ctx, List<Marker> fileparts) {
        for (var fp : fileparts) {
            boolean seenSeason = false;
            boolean seenEpisode = false;
            var localDup = ctx.matches.tagged(WEAK_DUPLICATE)
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .sorted(Comparator.comparingInt(Match::start).reversed())
                    .toList();
            var dropDup = new ArrayList<Match>();
            for (var m : localDup) {
                if (MatchName.SEASON == m.name()) {
                    if (seenSeason) dropDup.add(m); else seenSeason = true;
                } else if (MatchName.EPISODE == m.name()) {
                    if (seenEpisode) dropDup.add(m); else seenEpisode = true;
                }
            }
            for (var m : dropDup) ctx.matches.remove(m);
        }
    }

    /**
     * Per-filepart: drop weak-duplicate when SxxExx/season-word/episode-word
     * exists in the SAME filepart. Mirrors python RemoveWeakDuplicate dependency
     * on RemoveWeakIfSxxExx (filepart-scoped) — a weak-duplicate in the filename
     * filepart must NOT be dropped just because an upper dir contains "Season N".
     */
    private static void dropDuplicateWhenStrongInFilepart(ParseContext ctx, List<Marker> fileparts) {
        var toRemove = new ArrayList<Match>();
        for (var fp : fileparts) {
            if (!filepartHasStrongMarker(ctx, fp)) continue;
            ctx.matches.tagged(WEAK_DUPLICATE)
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .forEach(toRemove::add);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    /** True if {@code fp} contains any SxxExx / episode-word / season-word match. */
    private static boolean filepartHasStrongMarker(ParseContext ctx, Marker fp) {
        return ctx.matches.all()
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .anyMatch(m -> m.tags().contains(SXXEXX)
                        || m.tags().contains(EPISODE_WORD)
                        || m.tags().contains(SEASON_WORD));
    }
}
