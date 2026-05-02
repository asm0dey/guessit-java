package io.guessit.engine;

import java.util.List;

public final class Pipeline {
    private final List<Phase> phases;

    public Pipeline(List<Phase> phases) { this.phases = List.copyOf(phases); }

    public void run(ParseContext ctx) {
        for (var phase : phases) phase.apply(ctx);
    }
}
