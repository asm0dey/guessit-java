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
                    m.group(1), 700, Set.of("weak-episode", WEAK_DUPLICATE, "coexist"), false));
            ctx.matches.add(new Match(EPISODE, e, m.start(2), m.end(2),
                    m.group(2), 700, Set.of("weak-episode", WEAK_DUPLICATE, "coexist"), false));
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
        boolean animeContext = !ctx.input.isEmpty()
                && (ctx.input.charAt(0) == '[' || ctx.input.charAt(0) == '(')
                && ctx.markers.stream().anyMatch(mk -> "group".equals(mk.name()) && mk.start() <= 1);
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
        // Adjacent dash-separated weak-episode pairs (e.g. "Bleach - 313-314"):
        // both numbers are absolute episodes, not a compact-SSEE split of the
        // second. Drop weak-duplicate matches that overlap either side. Run
        // before the overlap-drop below so weak-episode 314 (and its sibling)
        // both survive as episode list members.
        if (!hasSxxExx) {
            var weakEpisodes = ctx.matches.named(EPISODE)
                    .filter(m -> m.tags().contains("weak-episode") && !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> m.value() instanceof Integer i && i >= 100)
                    .sorted(java.util.Comparator.comparingInt(Match::start))
                    .toList();
            var dropDup = new ArrayList<Match>();
            for (int i = 0; i + 1 < weakEpisodes.size(); i++) {
                var a = weakEpisodes.get(i);
                var b = weakEpisodes.get(i + 1);
                String gap = ctx.input.substring(a.end(), b.start());
                if (gap.length() < 1 || gap.length() > 5) continue;
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
                    .filter(m -> m.tags().contains("weak-episode") && !m.tags().contains(WEAK_DUPLICATE))
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
                    if (gap.length() < 1 || gap.length() > 5) continue;
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
                            Set.of("weak-episode"), false));
                    break;
                }
            }
            for (var m : toRemove) ctx.matches.remove(m);
            for (var m : toAdd) ctx.matches.add(m);
        }

        // Drop weak-episode matches that overlap with weak-duplicate matches —
        // skip when a SxxExx episode/season is present, since weak-duplicate
        // will be dropped below and we want the wider weak-episode to survive
        // for the absolute_episode rename (e.g. "Show.Name.s16e03-05.313-315").
        boolean sxxExxPresent = ctx.matches.named(EPISODE).anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("episode-word"))
                || ctx.matches.named(SEASON).anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("season-word"));
        var weakDuplicates = ctx.matches.all().filter(m -> m.tags().contains(WEAK_DUPLICATE)).toList();
        if (!weakDuplicates.isEmpty() && !sxxExxPresent) {
            var toRemove = ctx.matches.all()
                    .filter(m -> m.tags().contains("weak-episode") && !m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> weakDuplicates.stream().anyMatch(wd -> wd.overlaps(m)))
                    .toList();
            for (var m : toRemove) ctx.matches.remove(m);
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
                    .filter(m -> m.tags().contains("weak-episode") || m.tags().contains(WEAK_DUPLICATE))
                    .filter(m -> expectedTitles.stream().anyMatch(t -> t.start() <= m.start() && m.end() <= t.end()))
                    .toList();
            for (var m : insideExpected) ctx.matches.remove(m);
        }

        var years = ctx.matches.named("year").toList();
        var codecs = ctx.matches.all()
                .filter(x -> x.name().equals("video_codec") || x.name().equals("audio_codec"))
                .toList();
        var toRemove = new ArrayList<Match>();
        for (var name : new String[]{SEASON, EPISODE}) {
            for (var m : ctx.matches.named(name).toList()) {
                if (!m.tags().contains(WEAK_DUPLICATE)) continue;
                if (sxxExxPresent) {
                    toRemove.add(m);
                    continue;
                }
                if (years.stream().anyMatch(y -> y.overlaps(m))) {
                    toRemove.add(m);
                    continue;
                }
                if (codecs.stream().anyMatch(c -> c.overlaps(m))) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
