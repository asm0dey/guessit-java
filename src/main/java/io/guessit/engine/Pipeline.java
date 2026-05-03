package io.guessit.engine;

import java.util.List;

/**
 * Ordered runner for a fixed sequence of {@link Phase}s.
 *
 * <p>Phases mutate the shared {@link ParseContext} in registration order.
 * The pipeline is stateless beyond its phase list, so a single instance can
 * be reused across many parses provided the phases themselves are stateless.
 *
 * <p>The default pipeline is built by {@code io.guessit.rules.Rules.defaultPipeline()};
 * see {@code docs/architecture.md} for the phase-by-phase rationale.
 */
public final class Pipeline {
    private final List<Phase> phases;

    public Pipeline(List<Phase> phases) { this.phases = List.copyOf(phases); }

    /** Runs every phase against {@code ctx}, in order. */
    public void run(ParseContext ctx) {
        for (var phase : phases) phase.apply(ctx);
    }
}
