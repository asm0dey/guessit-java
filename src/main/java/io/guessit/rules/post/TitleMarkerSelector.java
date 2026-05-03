package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Comparator;

/**
 * Picks the {@code path} marker that contains the most matches as the
 * "title marker" — the one from which the title will later be derived from
 * unmatched gaps.
 *
 * <p>The most-matches heuristic mirrors Python guessit's
 * {@code TitleFromPosition} — the segment that produced the most properties
 * is the one most likely to actually describe the work, rather than a
 * peripheral folder name. Falls back to the {@code whole} marker (or a
 * synthetic one) when no path markers exist.
 */
public final class TitleMarkerSelector implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        Marker best = ctx.markers.stream()
            .filter(m -> m.name().equals("path"))
            .max(Comparator.comparingLong(m ->
                ctx.matches.all().filter(x -> m.covers(x.start(), x.end())).count()))
            .orElse(null);

        if (best == null) {
            best = ctx.markers.stream()
                .filter(m -> m.name().equals("whole"))
                .findFirst()
                .orElse(new Marker("whole", 0, ctx.input.length(), ctx.input));
        }
        ctx.titleMarker = best;
    }
}
