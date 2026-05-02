package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EpisodeDetailsExtractor implements Extractor {
    private static final List<String> DETAILS = List.of("Special", "Pilot", "Unaired", "Final");

    @Override public String name() { return "episode_details"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        for (var detail : DETAILS) {
            var opts = StringOpts.defaults().withValidator(m -> Validators.sepsSurround(input).test(m));
            var matches = PatternMatcher.string(input, Set.of(detail), "episode_details", opts);
            for (var m : matches) ctx.matches.add(m);
        }
    }

    /** Replicates Python EpisodeDetailValidator. */
    @Override
    public void postProcess(ParseContext ctx) {
        var details = ctx.matches.named("episode_details").toList();
        var toRemove = new ArrayList<Match>();
        for (var d : details) {
            boolean adjacentToEp = ctx.matches.all().anyMatch(m ->
                ("season".equals(m.name()) || "episode".equals(m.name()))
                    && (Math.abs(m.end() - d.start()) <= 1 || Math.abs(d.end() - m.start()) <= 1));
            if (!adjacentToEp && !Validators.sepsSurround(ctx.input).test(d)) {
                toRemove.add(d);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
