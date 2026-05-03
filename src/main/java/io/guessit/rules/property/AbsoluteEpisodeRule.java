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
        // Disabled: leading-weak-episode rename was too aggressive (broke title=<number> cases
        // like "12.Monkeys" / "24" / "4400"). Python's absolute_episode rule fires on TRAILING
        // numbers (e.g. "Bleach s16e03-04 313-314" → absolute_episode=[313,314]); leading-rename
        // is not part of upstream behavior.
    }
}
