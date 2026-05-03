package io.guessit.engine;

/**
 * Phase 3 — drop overlapping non-private matches via {@link ConflictSolver}.
 *
 * <p>Sits between extract and post-process so extractor {@code postProcess}
 * hooks operate on a clean, deconflicted match set; otherwise matches that
 * were already going to be dropped would still be visible to rename logic
 * and the output would diverge from Python guessit.
 */
public record ConflictPhase() implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ConflictSolver.solve(ctx.matches);
    }
}
