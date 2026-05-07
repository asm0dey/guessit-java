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
        ctx.trace.phase("conflicts", "resolving overlapping matches");
        var before = ctx.matches.snapshot();
        ConflictSolver.solve(ctx.matches, ctx.trace);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
    }
}
