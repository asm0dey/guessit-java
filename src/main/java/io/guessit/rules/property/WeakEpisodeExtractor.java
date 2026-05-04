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
        if (!rangePairedWeakPresent
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
        // Drop weaks if a SxxExx-tagged episode exists in the same filepart (RemoveWeakIfSxxExx).
        boolean strongPresent = ctx.matches.named(EPISODE).anyMatch(m -> m.tags().contains("SxxExx"));
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
        var weaks = ctx.matches.named(EPISODE)
            .filter(m -> m.tags().contains("weak-episode"))
            .filter(m -> !m.tags().contains("weak-duplicate"))
            .toList();
        for (var m : weaks) ctx.matches.remove(m);
    }
}
