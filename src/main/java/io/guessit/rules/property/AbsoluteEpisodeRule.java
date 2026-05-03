package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;

import java.util.ArrayList;
import java.util.Set;

/**
 * Renaming-only rule: promotes leading weak-episode matches to
 * {@code absolute_episode} when the same filepart also produced an
 * {@code SxxExx}-tagged episode.
 *
 * <p>Doesn't add any matches in {@link #extract}; the work is entirely in
 * {@link #postProcess}, after conflict resolution has finalised which weak
 * and strong episodes survived. Priority 600 has no real effect because
 * this extractor never produces a candidate that can lose a conflict; the
 * value documents intent.
 *
 * <p>Why this is a separate rule rather than logic inside
 * {@code SeasonEpisodeExtractor}: the relationship is a property of the
 * <em>surviving</em> match set, not of any single extractor's output. Doing
 * it here keeps each extractor focused on its own pattern catalogue.
 */
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
                .filter(_ -> sxxExxEpisodes.stream().anyMatch(s -> filepart.covers(s.start(), s.end())))
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
