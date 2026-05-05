package io.guessit.rules.post;

import io.guessit.engine.Markers;
import io.guessit.engine.Match;
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
    private final String targetName;

    public RemoveLessSpecificSeasonEpisode(String name) {
        this.targetName = name;
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
            m -> targetName.equals(m.name()) && m.tags().contains("SxxExx"));

        var seenNames = new HashSet<String>();
        var values = new HashMap<String, Set<Object>>();
        var toRemove = new ArrayList<Match>();

        var sxxTie = Comparator.comparing((Match m) -> m.tags().contains("SxxExx") ? 0 : 1);
        for (var fp : sorted) {
            var inFp = ctx.matches.snapshot().stream()
                .filter(m -> !m.isPrivate())
                .filter(m -> targetName.equals(m.name()))
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .sorted(sxxTie)
                .toList();
            var fpNames = new HashSet<String>();
            for (var m : inFp) {
                fpNames.add(m.name());
                var bucket = values.computeIfAbsent(m.name(), _ -> new LinkedHashSet<>());
                if (seenNames.contains(m.name())) {
                    if (!bucket.contains(m.value())) toRemove.add(m);
                } else {
                    bucket.add(m.value());
                }
            }
            seenNames.addAll(fpNames);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
