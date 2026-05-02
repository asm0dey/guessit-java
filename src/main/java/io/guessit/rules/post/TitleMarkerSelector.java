package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.PostPhase.PostProcessor;
import io.guessit.engine.ParseContext;

import java.util.Comparator;

public final class TitleMarkerSelector implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        Marker best = ctx.markers.stream()
            .filter(m -> m.name().equals("path"))
            .max(Comparator.comparingLong(m ->
                ctx.matches.all().filter(x -> m.covers(x.start(), x.end())).count()))
            .orElse(null);

        if (best == null) {
            best = ctx.markers.stream()
                .filter(m -> m.name().equals("whole"))
                .findFirst()
                .orElse(new Marker("whole", 0, ctx.input.length(), ctx.input));
        }
        ctx.titleMarker = best;
    }
}
