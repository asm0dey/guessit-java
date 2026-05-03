package io.guessit.engine;

import java.util.List;

/**
 * Phase 2 — call {@link Extractor#extract} on every registered extractor.
 *
 * <p>All extractors complete this pass before {@link ConflictPhase} runs, so
 * each extractor's candidates compete against every other extractor's
 * candidates rather than only against earlier-registered ones. Within this
 * phase, extractor order still matters whenever a later extractor reads tags
 * or matches added by an earlier one.
 */
public record ExtractorPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var e : extractors) e.extract(ctx);
    }
}
