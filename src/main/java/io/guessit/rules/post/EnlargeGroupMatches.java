package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase;

import java.util.ArrayList;

/**
 * Port of python {@code processors.EnlargeGroupMatches}: for each {@code group}
 * marker, any match that starts at {@code group.start + 1} is extended leftward
 * to {@code group.start}, and any match that ends at {@code group.end - 1} is
 * extended rightward to {@code group.end}.  This ensures the surrounding bracket
 * characters are included in the matched span.
 */
public final class EnlargeGroupMatches implements PostPhase.PostProcessor {
    @Override
    public String description() {
        return "enlarge match span to cover its containing bracket group";
    }

    @Override
    public void process(ParseContext ctx) {
        for (var g : ctx.markers) {
            if ("group".equals(g.name())) enlargeForGroup(ctx, g);
        }
    }

    private static void enlargeForGroup(ParseContext ctx, Marker g) {
        for (var m : new ArrayList<>(ctx.matches.snapshot())) {
            var next = enlargedMatch(m, g);
            if (next != null) ctx.matches.replace(m, next);
        }
    }

    private static Match enlargedMatch(Match m, Marker g) {
        Match next = null;
        if (m.start() == g.start() + 1 && m.end() <= g.end()) {
            next = m.withStart(g.start());
        }
        if (m.end() == g.end() - 1 && m.start() >= g.start()) {
            next = (next != null ? next : m).withEnd(g.end());
        }
        return next;
    }
}
