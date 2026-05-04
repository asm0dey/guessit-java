package io.guessit.rules.post;

import io.guessit.engine.Match;

import java.util.Comparator;

/**
 * Variant of {@link RemoveAmbiguous} for season/episode properties that
 * iterates fileparts in reverse order (last/filename part wins) and prefers
 * SxxExx-tagged matches when breaking ties within a single filepart.
 *
 * <p>This is a port of Python guessit's {@code RemoveLessSpecificSeasonEpisode}
 * post-processor.
 */
public final class RemoveLessSpecificSeasonEpisode extends RemoveAmbiguous {
    public RemoveLessSpecificSeasonEpisode(String name) {
        super(
            m -> m.name().equals(name),
            true,
            Comparator.comparing((Match m) -> m.tags().contains("SxxExx") ? 0 : 1)
        );
    }
}
