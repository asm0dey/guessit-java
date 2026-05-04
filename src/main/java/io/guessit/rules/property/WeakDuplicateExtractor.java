package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Seps;
import io.guessit.engine.Validators;

import java.util.ArrayList;
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
    public static final String WEAK_DUPLICATE = "weak-duplicate";
    public static final String SEASON = "season";
    public static final String EPISODE = "episode";
    public static final String WEAK_EPISODE = "weak-episode";

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
            var span = new Match("weak", null, m.start(), m.end(), m.group(), 700, Set.of(), false);
            if (!seps.test(span)) continue;
            int s = Integer.parseInt(m.group(1));
            int e = Integer.parseInt(m.group(2));
            ctx.matches.add(new Match(SEASON, s, m.start(1), m.end(1),
                    m.group(1), 700, Set.of(WEAK_EPISODE, WEAK_DUPLICATE, "coexist"), false));
            ctx.matches.add(new Match(EPISODE, e, m.start(2), m.end(2),
                    m.group(2), 700, Set.of(WEAK_EPISODE, WEAK_DUPLICATE, "coexist"), false));
        }
    }

    /**
     * Replicates RemoveWeakDuplicate: drop the weak-duplicate pair when a strong SxxExx exists.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        // Anime-context: input begins with a [Group] / (Group) marker and the
        // standalone 3-4 digit number is the absolute episode, not a compact
        // SSEE pair. Drop weak-duplicate so the wider weak-episode survives
        // (e.g. "[Fansub] One Piece 603" → episode=603).
        // Anime-context is only valid when the leading bracket contains a
        // non-numeric group name (e.g. "[Fansub]"). If the bracket contains
        // only digits (e.g. "[401]"), it IS the compact SSEE — do not drop.
        boolean animeContext = !ctx.input.isEmpty()
                && (ctx.input.charAt(0) == '[' || ctx.input.charAt(0) == '(')
                && ctx.markers.stream().anyMatch(mk -> "group".equals(mk.name()) && mk.start() <= 1
                        && !mk.raw().matches("\\d+"));
        if (animeContext) {
            var dropDup = ctx.matches.all()
                    .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                    .toList();
            for (var m : dropDup) ctx.matches.remove(m);
        }

        // Anime non-bracket: NNN preceded by "[sep]-[sep]" with anime decoration
        // following ([HD], [720p], (year), etc.) and no SxxExx anywhere means
        // the digits are an absolute episode, not a compact SSEE pair. Bare
        // "Title - NNN" (NNN at end of input) is treated as compact SSEE.
        boolean hasSxxExx = ctx.matches.all().anyMatch(m -> m.tags().contains("SxxExx"));
        if (!hasSxxExx) {
            var seasonRuns = ctx.matches.named(SEASON)
                    .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> isDashSeparatedBefore(ctx.input, m.start()))
                    .toList();
            for (var seasonMatch : seasonRuns) {
                // Episode of same weak-duplicate run starts at seasonMatch.end().
                var matchingEpisode = ctx.matches.named(EPISODE)
                        .filter(em -> em.tags().contains(WEAK_DUPLICATE) && em.start() == seasonMatch.end())
                        .findFirst().orElse(null);
                if (matchingEpisode == null) continue;
                int runEnd = matchingEpisode.end();
                if (!hasContentAfterPos(ctx.input, runEnd)) continue;
                ctx.matches.remove(seasonMatch);
                ctx.matches.remove(matchingEpisode);
            }
        }
        // Adjacent dash-separated weak-episode pairs (e.g. "Bleach - 313-314",
        // "003-005"): both numbers are absolute episodes. Drop weak-duplicate
        // matches that overlap the pair so the SSEE split (3+14) is
        // suppressed; PostPhase RangeFiller then expands the inclusive range
        // between the two surviving weak-episode endpoints. Run before the
        // overlap-drop below so weak-episode 314 (and its sibling) both
        // survive as episode list members.
        if (!hasSxxExx) {
            var weakEpisodes = ctx.matches.named(EPISODE)
                    .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.raw() != null && m.raw().length() >= 3)
                    .sorted(java.util.Comparator.comparingInt(Match::start))
                    .toList();
            var dropDup = new ArrayList<Match>();
            for (int i = 0; i + 1 < weakEpisodes.size(); i++) {
                var a = weakEpisodes.get(i);
                var b = weakEpisodes.get(i + 1);
                if (!(a.value() instanceof Integer va) || !(b.value() instanceof Integer vb)) continue;
                if (vb <= va) continue;
                String gap = ctx.input.substring(a.end(), b.start());
                if (gap.isEmpty() || gap.length() > 5) continue;
                boolean isRangeSep = gap.matches("[ ._]*[-~][ ._]*");
                if (!isRangeSep) continue;
                // Drop weak-duplicate season/episode matches inside [a.start, b.end].
                var inside = ctx.matches.all()
                        .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                        .filter(m -> m.start() >= a.start() && m.end() <= b.end())
                        .toList();
                dropDup.addAll(inside);
            }
            for (var m : dropDup) ctx.matches.remove(m);
        }

        // Also detect "weak-episode (≥100) - weak-duplicate-pair" form: when
        // weak-duplicate already consumed the second number ("314" → 3+14)
        // but a leading weak-episode ≥100 sits dash-separated before, drop
        // the duplicate split so a wider weak-episode regenerates is
        // unnecessary — we just keep the leading weak-episode and re-add the
        // second number as a plain weak-episode match.
        if (!hasSxxExx) {
            var leadingWeak = ctx.matches.named(EPISODE)
                    .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.value() instanceof Integer i && i >= 100)
                    .sorted(java.util.Comparator.comparingInt(Match::start))
                    .toList();
            var dupSeasons = ctx.matches.named(SEASON)
                    .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                    .sorted(java.util.Comparator.comparingInt(Match::start))
                    .toList();
            var toRemove = new ArrayList<Match>();
            var toAdd = new ArrayList<Match>();
            for (var lead : leadingWeak) {
                for (var dupS : dupSeasons) {
                    if (dupS.start() < lead.end()) continue;
                    String gap = ctx.input.substring(lead.end(), dupS.start());
                    if (gap.isEmpty() || gap.length() > 5) continue;
                    if (!gap.matches("[ ._]*[-~][ ._]*")) continue;
                    // Pair end = matching weak-duplicate episode.
                    var dupE = ctx.matches.named(EPISODE)
                            .filter(em -> em.tags().contains(WEAK_DUPLICATE) && em.start() == dupS.end())
                            .findFirst().orElse(null);
                    if (dupE == null) continue;
                    int spanStart = dupS.start();
                    int spanEnd = dupE.end();
                    int combined = Integer.parseInt(ctx.input.substring(spanStart, spanEnd));
                    toRemove.add(dupS);
                    toRemove.add(dupE);
                    toAdd.add(new Match(EPISODE, combined, spanStart, spanEnd,
                            ctx.input.substring(spanStart, spanEnd), 800,
                            Set.of(WEAK_EPISODE), false));
                    break;
                }
            }
            for (var m : toRemove) ctx.matches.remove(m);
            for (var m : toAdd) ctx.matches.add(m);
        }

        // RemoveWeakIfMovie analogue: per-filepart, when type != episode and a
        // year exists, drop weak-duplicate matches in that filepart UNLESS the
        // weak-duplicate sits immediately after the year (no hole between
        // year.end and the weak-duplicate match). Python's RemoveWeakIfMovie
        // exempts the next match's initiator when contiguous with the year, so
        // "the.flash.2014.208" keeps 2+08 as season+episode. "123.Angry.Men.1957"
        // has the weak-duplicate BEFORE the year → no exemption → drop.
        if (!EPISODE.equals(ctx.options.type())) {
            var movieFileparts = io.guessit.engine.Markers.named(ctx.markers, "path").toList();
            var dropForMovie = new ArrayList<Match>();
            for (var fp : movieFileparts) {
                var year = ctx.matches.named("year")
                        .filter(y -> y.start() >= fp.start() && y.end() <= fp.end())
                        .findFirst().orElse(null);
                if (year == null) continue;
                int yearEnd = year.end();
                // Identify the weak-duplicate PAIR (season + episode at same position)
                // immediately following year via separator-only gap; exempt that pair.
                var pairStarts = new java.util.HashSet<Integer>();
                ctx.matches.named(SEASON)
                        .filter(s -> s.tags().contains(WEAK_DUPLICATE))
                        .filter(s -> s.start() >= fp.start() && s.end() <= fp.end())
                        .filter(s -> s.start() >= yearEnd)
                        .filter(s -> {
                            String gap = ctx.input.substring(yearEnd, s.start());
                            return gap.chars().allMatch(c -> Seps.isSep((char) c));
                        })
                        .forEach(s -> pairStarts.add(s.start()));
                ctx.matches.all()
                        .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                        .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                        .filter(m -> {
                            // Exempt season match itself
                            if (pairStarts.contains(m.start())) return false;
                            // Exempt episode match of the same pair (starts at season.end == season.start+1or2)
                            for (int ps : pairStarts) {
                                // Episode of pair starts at season.end. Season raw is 1-2 chars,
                                // so episode start is ps+1 or ps+2.
                                if (m.start() == ps + 1 || m.start() == ps + 2) {
                                    var prev = ctx.matches.named(SEASON)
                                            .filter(s -> s.start() == ps && s.tags().contains(WEAK_DUPLICATE))
                                            .findFirst().orElse(null);
                                    if (prev != null && m.start() == prev.end()) return false;
                                }
                            }
                            return true;
                        })
                        .forEach(dropForMovie::add);
            }
            for (var m : dropForMovie) ctx.matches.remove(m);
        }

        // Pre-clean weak-duplicate inside expected-title spans. The user told
        // us those digits are part of the title, so they must not influence
        // the dedup or weak-episode removal below.
        var preExpectedTitles = ctx.matches.all()
                .filter(m -> "title".equals(m.name()) && m.tags().contains("expected"))
                .toList();
        if (!preExpectedTitles.isEmpty()) {
            var dropInsideExpected = ctx.matches.all()
                    .filter(m -> SEASON.equals(m.name()) || EPISODE.equals(m.name()))
                    .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> preExpectedTitles.stream().anyMatch(t -> t.start() <= m.start() && m.end() <= t.end()))
                    .toList();
            for (var m : dropInsideExpected) ctx.matches.remove(m);
        }

        // Pre-clean weak-duplicate matches that overlap a stronger property
        // (year, date, video_codec, audio_codec). These overlaps would have
        // been resolved by python's default longest-wins conflict solver
        // before WeakConflictSolver/RemoveWeakDuplicate run. Doing this BEFORE
        // the dedup pass prevents a year-shaped weak-duplicate (e.g. "2010"
        // → 20+10) from beating a real episode-shaped weak-duplicate ("100" →
        // 1+00) just because it appears later in the input.
        var prePropYears = ctx.matches.named("year").toList();
        var prePropDates = ctx.matches.named("date").toList();
        var prePropCodecs = ctx.matches.all()
                .filter(x -> x.name().equals("video_codec") || x.name().equals("audio_codec"))
                .toList();
        var preClean = new ArrayList<Match>();
        for (var name : new String[]{SEASON, EPISODE}) {
            for (var m : ctx.matches.named(name).toList()) {
                if (!m.tags().contains(WEAK_DUPLICATE)) continue;
                if (prePropYears.stream().anyMatch(y -> y.overlaps(m))
                        || prePropDates.stream().anyMatch(d -> d.overlaps(m))
                        || prePropCodecs.stream().anyMatch(c -> c.overlaps(m))) {
                    preClean.add(m);
                }
            }
        }
        for (var m : preClean) ctx.matches.remove(m);

        // Per-filepart: when weak-duplicate exists and no SxxExx in the same
        // filepart, drop ALL weak-episode in that filepart (mirror Python
        // WeakConflictSolver "elif weak_dup_matches: ... if not episodes_in_range
        // and not SxxExx: to_remove.extend(weak_matches)"). Java's
        // WeakEpisodeExtractor doesn't chain weak-episodes with separators,
        // so the "episodes_in_range" branch is empty.
        var fileparts = io.guessit.engine.Markers.named(ctx.markers, "path").toList();
        for (var fp : fileparts) {
            var localDup = ctx.matches.all()
                    .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .toList();
            if (localDup.isEmpty()) continue;
            boolean localSxxExx = ctx.matches.all()
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("episode-word") || m.tags().contains("season-word"));
            if (localSxxExx) continue;
            var dropWeakEp = ctx.matches.all()
                    .filter(m -> m.tags().contains(WEAK_EPISODE) && !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .toList();
            for (var m : dropWeakEp) ctx.matches.remove(m);
        }

        // Per-filepart: when multiple weak-duplicate matches share the same
        // pattern (here all from PATTERN), drop earlier occurrences keeping
        // only the LAST. Mirror Python RemoveWeakDuplicate: iterate in reverse
        // and drop matches whose pattern was already seen. Each weak-duplicate
        // pair contributes one season match and one episode match — dedup
        // them independently so both sides of the surviving pair stay.
        // Example: "the.100.109" → 1+00 and 1+09. Reverse-iter keeps 1+09, drops 1+00.
        for (var fp : fileparts) {
            boolean seenSeason = false;
            boolean seenEpisode = false;
            var localDup = ctx.matches.all()
                    .filter(m -> m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .sorted(java.util.Comparator.comparingInt(Match::start).reversed())
                    .toList();
            var dropDup = new ArrayList<Match>();
            for (var m : localDup) {
                if (SEASON.equals(m.name())) {
                    if (seenSeason) dropDup.add(m); else seenSeason = true;
                } else if (EPISODE.equals(m.name())) {
                    if (seenEpisode) dropDup.add(m); else seenEpisode = true;
                }
            }
            for (var m : dropDup) ctx.matches.remove(m);
        }

        // Drop weak-duplicate (and overlapping weak-episode) matches that fall
        // entirely inside an expected-title span: the user told us those digits
        // are part of the title, not a season/episode pair.
        var expectedTitles = ctx.matches.all()
                .filter(m -> "title".equals(m.name()) && m.tags().contains("expected"))
                .toList();
        if (!expectedTitles.isEmpty()) {
            var insideExpected = ctx.matches.all()
                    .filter(m -> SEASON.equals(m.name()) || EPISODE.equals(m.name()))
                    .filter(m -> m.tags().contains(WEAK_EPISODE) || m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> expectedTitles.stream().anyMatch(t -> t.start() <= m.start() && m.end() <= t.end()))
                    .toList();
            for (var m : insideExpected) ctx.matches.remove(m);
        }

        // Per-filepart: drop weak-duplicate when a SxxExx/season-word/episode-word
        // exists in the same filepart. Mirror python RemoveWeakDuplicate
        // dependency on RemoveWeakIfSxxExx (filepart-scoped). A weak-duplicate
        // in the filename filepart must NOT be dropped just because an upper
        // dir contains "Season N".
        var toRemove = new ArrayList<Match>();
        for (var fp : fileparts) {
            boolean localStrong = ctx.matches.all()
                    .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                    .anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("episode-word") || m.tags().contains("season-word"));
            if (!localStrong) continue;
            for (var m : ctx.matches.all().toList()) {
                if (!m.tags().contains(WEAK_DUPLICATE)) continue;
                if (m.start() >= fp.start() && m.end() <= fp.end()) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
