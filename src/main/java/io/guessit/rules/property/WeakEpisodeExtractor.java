package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    public static final String EPISODE = "episode";

    @Override public String name() { return "weak_episode"; }
    @Override public int priority() { return 800; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        // Pre-compute SxxExx episodes; skip weak matches that overlap them.
        var protectedEpisodes = ctx.matches.named(EPISODE)
            .filter(m -> m.tags().contains("SxxExx"))
            .toList();

        emit(ctx, input, TWO_DIGIT, seps, protectedEpisodes);
        emit(ctx, input, THREE_OR_FOUR, seps, protectedEpisodes);
        if (EPISODE.equals(ctx.options.type())) {
            emit(ctx, input, SINGLE, seps, protectedEpisodes);
        }
    }

    private void emit(ParseContext ctx, String input, Pattern p, java.util.function.Predicate<Match> seps,
                      List<Match> protectedEpisodes) {
        var m = p.matcher(input);
        while (m.find()) {
            int ms = m.start(1);
            int me = m.end(1);
            int validateEnd = m.end();
            if (overlapsAnyProtected(ms, me, protectedEpisodes)) continue;

            var head = new Match(EPISODE, null, ms, validateEnd, m.group(1), 800, Set.of("weak-episode"), false);
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            ctx.matches.add(new Match(EPISODE, v, ms, me,
                    m.group(1), 800, Set.of("weak-episode"), false));
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
        // Range-paired ≥100 weak-episodes ("Show.Name.-.476-479.(2007)...")
        // are episode ranges, not movie noise — exempt them from the
        // year-triggered movie removal so RangeFiller can expand the pair.
        boolean rangePairedWeakPresent = hasRangePairedWeakEpisodes(ctx);
        // Anime context: a screen_size match inside any group marker means
        // the input is an anime release ("Show.Name.2.-.10.(2016).[HorribleSubs]
        // [WEBRip]..[HD.720p]"). In that shape weak-episodes carry the
        // absolute episode number, even with a year present, so don't purge.
        boolean animeContext = false;
        for (var mk : ctx.markers) {
            if (!"group".equals(mk.name())) continue;
            boolean hasScreenInGroup = ctx.matches.named("screen_size")
                    .anyMatch(m -> m.start() >= mk.start() && m.end() <= mk.end());
            if (hasScreenInGroup) { animeContext = true; break; }
        }
        if (!rangePairedWeakPresent && !animeContext
                && ctx.matches.named("year").findAny().isPresent()
                && !EPISODE.equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return;
        }
        if ("movie".equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return;
        }

        // Drop weak episodes that directly follow an audio_codec/source/screen_size/streaming_service match.
        var blockingNames = Set.of("audio_codec", "screen_size", "streaming_service",
            "source", "video_profile", "audio_channels", "audio_profile");
        var blocking = ctx.matches.all().filter(m -> blockingNames.contains(m.name())).toList();
        var weaks = ctx.matches.named(EPISODE).filter(m -> m.tags().contains("weak-episode")).toList();
        var toRemove = new ArrayList<Match>();
        for (var weak : weaks) {
            for (var b : blocking) {
                if (b.end() <= weak.start() && weak.start() - b.end() <= 3) {
                    String gap = ctx.input.substring(b.end(), weak.start());
                    if (gap.chars().allMatch(c -> Seps.isSep((char) c))) {
                        toRemove.add(weak);
                        break;
                    }
                }
            }
        }
        // Drop weaks if any SxxExx-tagged match exists in the same filepart
        // (RemoveWeakIfSxxExx). Python checks ANY non-private match with the
        // SxxExx tag — a season-only match (e.g. "S32" without explicit
        // episode) is enough to suppress trailing weak episode candidates.
        // Skip SxxExx matches that overlap a title span: they're going to be
        // dropped by the title (e.g. expected_title="Show Name S2" subsumes
        // the S2 season match), so they don't actually anchor an SxxExx.
        var titleSpans = ctx.matches.named("title")
            .map(m -> new int[]{m.start(), m.end()}).toList();
        var fileparts = io.guessit.engine.Markers.named(ctx.markers, "path").toList();
        // Episode-level SxxExx (e.g. S02E05) anchors weak removal across ALL
        // fileparts: an inner filepart's bare numeric ("160725_02.mkv") must
        // not steal the outer's canonical episode via PreferLastPath.
        boolean anyEpisodeSxxExx = ctx.matches.named(EPISODE)
            .anyMatch(m -> !m.isPrivate() && m.tags().contains("SxxExx")
                && titleSpans.stream().noneMatch(t -> t[0] <= m.start() && m.end() <= t[1]));
        // Season-only SxxExx (e.g. S01 with no Exx) only anchors weak
        // removal within its own filepart — sibling fileparts retain their
        // weak_duplicate matches (e.g. inner "show.name.0106.…" carries the
        // real episode number when outer only declared the season).
        var seasonStrongSpans = ctx.matches.all()
            .filter(m -> !m.isPrivate() && m.tags().contains("SxxExx")
                && "season".equals(m.name())
                && titleSpans.stream().noneMatch(t -> t[0] <= m.start() && m.end() <= t[1]))
            .map(m -> new int[]{m.start(), m.end()})
            .toList();
        java.util.function.Predicate<Match> strongInFilepart = w -> {
            if (anyEpisodeSxxExx) return true;
            for (var fp : fileparts) {
                if (w.start() < fp.start() || w.end() > fp.end()) continue;
                for (var sp : seasonStrongSpans) {
                    if (sp[0] >= fp.start() && sp[1] <= fp.end()) return true;
                }
                return false;
            }
            return !seasonStrongSpans.isEmpty();
        };
        boolean strongPresent = weaks.stream().anyMatch(strongInFilepart);
        if (strongPresent) {
            // Mirror Python's RemoveWeakIfSxxExx + RenameToAbsoluteEpisode +
            // EpisodeNumberSeparatorRange. For each non-leading weak:
            //  - same filepart, contiguous to a preceding episode (only sep
            //    chars between), value < 100: keep as episode so it joins the
            //    SxxExx list ("E01 02 03" → [1,2,3]).
            //  - same filepart, contiguous, value >= 100 and ≥2 high-value
            //    weaks present: rename all to absolute_episode (313-314 form).
            //  - otherwise drop the weak.
            var allEpisodes = ctx.matches.named(EPISODE)
                .sorted(java.util.Comparator.comparingInt(Match::start))
                .toList();
            var paths = io.guessit.engine.Markers.named(ctx.markers, "path").toList();
            long highWeakCount = weaks.stream()
                .filter(w -> w.start() != 0 && w.value() instanceof Integer i && i >= 100)
                .count();
            for (var weak : weaks) {
                if (!strongInFilepart.test(weak)) continue;
                // Keep weak if it sits at the start of its filepart (mirror python's
                // RemoveWeakIfSxxExx: leading weak_episode at filepart.start survives).
                boolean leading = false;
                for (var fp : fileparts) {
                    if (weak.start() == fp.start() && weak.end() <= fp.end()) {
                        leading = true; break;
                    }
                }
                if (leading) continue;
                if (weak.start() == 0) continue;
                int v = weak.value() instanceof Integer i ? i : -1;
                Match prev = null;
                for (var ep : allEpisodes) {
                    if (ep == weak) continue;
                    if (ep.end() <= weak.start()) prev = ep;
                    else break;
                }
                boolean contiguous = false;
                boolean sameFilepart = false;
                if (prev != null) {
                    String gap = ctx.input.substring(prev.end(), weak.start());
                    contiguous = gap.chars().allMatch(c -> Seps.isSep((char) c));
                    for (var fp : paths) {
                        if (prev.start() >= fp.start() && prev.end() <= fp.end()
                            && weak.start() >= fp.start() && weak.end() <= fp.end()) {
                            sameFilepart = true;
                            break;
                        }
                    }
                }
                if (contiguous && sameFilepart && v < 100) {
                    // Numeric-proximity guard: keep as episode only when value is
                    // a small step from prev (mirrors Python's separator-range
                    // semantics — sequential or short range). Larger jumps mean
                    // the digits belong to title content like "S02E02.65.Million.Years".
                    int prevVal = prev.value() instanceof Integer pi ? pi : -1;
                    if (prevVal > 0 && (v - prevVal) > 5) {
                        toRemove.add(weak);
                        continue;
                    }
                    continue;
                }
                if (v >= 100 && (highWeakCount >= 2 || !contiguous)) {
                    ctx.matches.add(new Match("absolute_episode", weak.value(), weak.start(), weak.end(),
                        weak.raw(), weak.priority(), weak.tags(), weak.isPrivate()));
                }
                toRemove.add(weak);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean hasRangePairedWeakEpisodes(ParseContext ctx) {
        var weaks = ctx.matches.named(EPISODE)
                .filter(m -> m.tags().contains("weak-episode") && !m.tags().contains("weak-duplicate"))
                .filter(m -> m.value() instanceof Integer i && i >= 100)
                .sorted(java.util.Comparator.comparingInt(Match::start))
                .toList();
        for (int i = 0; i + 1 < weaks.size(); i++) {
            var a = weaks.get(i);
            var b = weaks.get(i + 1);
            if (!(a.value() instanceof Integer va) || !(b.value() instanceof Integer vb)) continue;
            if (vb <= va) continue;
            int gapLen = b.start() - a.end();
            if (gapLen <= 0 || gapLen > 5) continue;
            String gap = ctx.input.substring(a.end(), b.start());
            if (gap.matches("[ ._]*[-~][ ._]*")) return true;
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
        var weaks = ctx.matches.named(EPISODE)
            .filter(m -> m.tags().contains("weak-episode"))
            .filter(m -> !(m.tags().contains("weak-duplicate")
                    && !inAnyGroupMarker(ctx, m)))
            .toList();
        var weakSeasons = ctx.matches.named("season")
            .filter(m -> m.tags().contains("weak-episode") && m.tags().contains("weak-duplicate"))
            .filter(m -> inAnyGroupMarker(ctx, m))
            .toList();
        for (var m : weaks) ctx.matches.remove(m);
        for (var m : weakSeasons) ctx.matches.remove(m);
    }

    private static boolean inAnyGroupMarker(ParseContext ctx, Match m) {
        return ctx.markers.stream()
            .anyMatch(mk -> "group".equals(mk.name())
                && mk.start() <= m.start() && mk.end() >= m.end());
    }
}
