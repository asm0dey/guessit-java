package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts the {@code episode_details} flags: Special, Pilot, Unaired, Final.
 *
 * <p>The post-pass keeps a flag only when at least one <em>structural</em>
 * match (season/episode/year/source/codec/etc.) sits to its left. The reason:
 * these short words also frequently appear in titles ("Final Destination",
 * "The Pilot"), so without anchoring evidence the match is almost always a
 * title fragment rather than a release flag.
 */
public final class EpisodeDetailsExtractor implements Extractor {
    private static final List<String> DETAILS = List.of("Special", "Pilot", "Unaired", "Final");

    @Override public String name() { return "episode_details"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        for (var detail : DETAILS) {
            var opts = StringOpts.defaults().withValidator(m -> Validators.sepsSurround(input).test(m));
            var matches = PatternMatcher.string(input, Set.of(detail), "episode_details", opts);
            for (var m : matches) ctx.matches.add(m);
        }
    }

    /**
     * Mirrors Python EpisodeDetailValidator: drop episode_details only when NOT
     * sep-surrounded AND no season/episode neighbour exists. Sep-surrounded
     * means the match sits between separator chars, which already filters out
     * in-word noise like "FinalCut".
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var details = ctx.matches.named("episode_details").toList();
        var toRemove = new ArrayList<Match>();
        var seps = Validators.sepsSurround(ctx.input);
        for (var d : details) {
            if (seps.test(d)) continue;
            boolean seasonOrEpisode = ctx.matches.all()
                .anyMatch(m -> ("season".equals(m.name()) || "episode".equals(m.name())));
            if (!seasonOrEpisode) toRemove.add(d);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
