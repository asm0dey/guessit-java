package io.guessit.engine;

import java.util.List;

/**
 * Phase 1 — populate {@link ParseContext#markers}.
 *
 * <p>Markers carve the input into named regions (whole / path / group) that
 * later phases use for scoping. Runs before any extractor so every match
 * created later can be tested for marker membership without ordering hazards.
 */
public record MarkerPhase(List<MarkerProducer> producers) implements Phase {
    /** Stateless callback invoked once per parse; appends markers to {@link ParseContext#markers}. */
    @FunctionalInterface
    public interface MarkerProducer {
        void produce(ParseContext ctx);
    }

    public MarkerPhase { producers = List.copyOf(producers); }

    @Override
    public void apply(ParseContext ctx) {
        for (var p : producers) p.produce(ctx);
    }
}
