package io.guessit.rules.property;

import io.guessit.engine.*;

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

    @Override public String name() { return MatchName.EPISODE_DETAILS.toString().toLowerCase(); }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        for (var detail : DETAILS) {
            var opts = StringOpts.defaults().withValidator(m -> Validators.sepsSurround(input).test(m));
            var matches = PatternMatcher.string(input, Set.of(detail), MatchName.EPISODE_DETAILS, opts, ctx.trace);
            for (var m : matches) ctx.matches.add(m);
        }
    }

}
