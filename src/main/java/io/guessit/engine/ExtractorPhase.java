package io.guessit.engine;

import java.util.List;

public record ExtractorPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var e : extractors) e.extract(ctx);
    }
}
