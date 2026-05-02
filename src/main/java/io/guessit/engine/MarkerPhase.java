package io.guessit.engine;

import java.util.List;

public record MarkerPhase(List<MarkerProducer> producers) implements Phase {
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
