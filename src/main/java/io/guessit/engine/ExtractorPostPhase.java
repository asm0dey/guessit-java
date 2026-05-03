package io.guessit.engine;

import java.util.List;

/**
 * Phase 4 — call {@link Extractor#postProcess} on every extractor.
 *
 * <p>Runs after {@link ConflictPhase}, so extractors see only the survivors
 * and can make decisions that depend on what stuck (e.g. renaming leading
 * numerics to {@code absolute_episode} only when an {@code SxxExx} episode
 * survived in the same filepart).
 */
public record ExtractorPostPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPostPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var e : extractors) e.postProcess(ctx);
    }
}
