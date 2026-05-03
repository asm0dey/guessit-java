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

    /**
     * Mirrors Python TitleFromPosition's removal of episode_details inside the title hole:
     * Special/Pilot/Unaired/Final survive only when at least one structural match precedes them
     * (i.e., they are no longer in the title region).
     */
    private static final Set<String> STRUCTURAL = Set.of(
        "season", "episode", "year", "date", "source", "video_codec", "audio_codec",
        "audio_channels", "audio_profile", "video_profile", "screen_size",
        "streaming_service", "website", "container", "episode_format", "release_group");

    @Override
    public void postProcess(ParseContext ctx) {
        var details = ctx.matches.named("episode_details").toList();
        var toRemove = new ArrayList<Match>();
        for (var d : details) {
            boolean structuralBefore = ctx.matches.all()
                .anyMatch(m -> STRUCTURAL.contains(m.name()) && m.start() < d.start());
            if (!structuralBefore) toRemove.add(d);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
