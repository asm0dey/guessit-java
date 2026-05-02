package io.guessit.engine;

import java.util.List;

public record ExtractorPostPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPostPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var e : extractors) e.postProcess(ctx);
    }
}
