package io.guessit.engine;

import java.util.List;

public record PostPhase(List<PostProcessor> processors) implements Phase {
    @FunctionalInterface
    public interface PostProcessor {
        void process(ParseContext ctx);
    }

    public PostPhase { processors = List.copyOf(processors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var p : processors) p.process(ctx);
    }
}
