package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;

public final class AbsoluteEpisodeRule implements Extractor {

    @Override public String name() { return "absolute_episode"; }
    @Override public int priority() { return 600; }

    @Override
    public void extract(ParseContext ctx) {
        // No-op: episode matches are created by WeakEpisodeExtractor.
        // This extractor only renames in postProcess().
    }

    /**
     * Replicates Python's RenameToAbsoluteEpisode: for each filepart, if there are
     * both SxxExx-tagged episode matches and leading weak-episode matches (at the
     * start of the filepart), rename the leading matches to "absolute_episode".
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var sxxExxEpisodes = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("SxxExx"))
            .toList();
        if (sxxExxEpisodes.isEmpty()) return;

        var toRename = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            // Find episode matches starting at the very beginning of this filepart
            // that are NOT SxxExx-tagged (i.e., came from WeakEpisodeExtractor)
            var leading = ctx.matches.named("episode")
                .filter(m -> !m.tags().contains("SxxExx"))
                .filter(m -> m.start() == filepart.start())
                .filter(m -> sxxExxEpisodes.stream().anyMatch(s -> filepart.covers(s.start(), s.end())))
                .toList();
            toRename.addAll(leading);
        }

        for (var m : toRename) {
            ctx.matches.add(new Match("absolute_episode", m.value(), m.start(), m.end(),
                m.raw(), m.priority(), Set.of("absolute_episode"), false));
            ctx.matches.remove(m);
        }
    }
}
