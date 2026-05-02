package io.guessit.engine;

public record ConflictPhase() implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ConflictSolver.solve(ctx.matches);
    }
}
