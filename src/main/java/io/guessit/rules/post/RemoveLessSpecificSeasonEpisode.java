package io.guessit.rules.post;

import io.guessit.engine.Markers;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.*;

/**
 * Port of python guessit's {@code RemoveLessSpecificSeasonEpisode}: iterate
 * fileparts in marker_sorted order using a {@code SxxExx}-tagged-match-with-
 * this-name predicate (filepart with most {@code SxxExx} matches first;
 * rightmost wins on tie), preferring {@code SxxExx} matches when several
 * fileparts have the same name.
 *
 * <p>For palindrome-tail filenames (e.g. {@code .../70E10S.5102....mkv})
 * the reversed copy yields fallback {@code season}/{@code episode} matches
 * that are NOT {@code SxxExx}-tagged. The outer filepart's real
 * {@code S01E07} pattern IS tagged, so it sorts first and its values win.
 */
public final class RemoveLessSpecificSeasonEpisode implements PostProcessor {
    private final MatchName targetName;

    public RemoveLessSpecificSeasonEpisode(MatchName name) {
        this.targetName = name;
    }

    @Override
    public String description() {
        return "drop generic season/episode when a more specific one survives";
    }

    @Override
    public void process(ParseContext ctx) {
        var paths = ctx.markers.stream()
            .filter(m -> "path".equals(m.name()))
            .toList();
        if (paths.isEmpty()) return;

        var reversed = new ArrayList<>(paths);
        Collections.reverse(reversed);
        var sorted = Markers.markerSorted(reversed, ctx.matches,
            m -> m.name() == targetName && m.tags().contains("SxxExx"));

        var sxxTie = Comparator.comparing((Match m) -> m.tags().contains("SxxExx") ? 0 : 1);
        var perFilepart = new ArrayList<List<Match>>();
        for (var fp : sorted) {
            var inFp = ctx.matches.snapshot().stream()
                .filter(m -> !m.isPrivate())
                .filter(m -> m.name() == targetName)
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .sorted(sxxTie)
                .toList();
            perFilepart.add(inFp);
        }
        RemoveAmbiguous.applyBucketDedup(ctx, perFilepart);
    }
}
