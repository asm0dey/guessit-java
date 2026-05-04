package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * For each property name, the first filepart's values win; later fileparts
 * only keep values that were already seen in an earlier filepart.
 *
 * <p>This is a port of Python guessit's {@code RemoveAmbiguous} post-processor.
 */
public class RemoveAmbiguous implements PostProcessor {
    protected final Predicate<Match> predicate;
    protected final boolean reverseFileparts;
    protected final Comparator<Match> tieBreak;

    public RemoveAmbiguous() {
        this(_ -> true, false, (_, _) -> 0);
    }

    public RemoveAmbiguous(Predicate<Match> predicate, boolean reverseFileparts, Comparator<Match> tieBreak) {
        this.predicate = predicate;
        this.reverseFileparts = reverseFileparts;
        this.tieBreak = tieBreak;
    }

    @Override
    public void process(ParseContext ctx) {
        // Mirror python: iterate fileparts in marker_sorted order (most-
        // valuable filepart first), so its values win when later fileparts
        // have differently-valued same-named matches.
        var pathsSorted = ctx.markers.stream()
            .filter(m -> "path".equals(m.name()))
            .toList();
        var paths = new ArrayList<>(io.guessit.engine.Markers.markerSorted(pathsSorted, ctx.matches));
        if (reverseFileparts) Collections.reverse(paths);

        var seenNames = new HashSet<String>();
        var values = new HashMap<String, Set<Object>>();
        var toRemove = new ArrayList<Match>();

        for (var fp : paths) {
            var inFp = ctx.matches.snapshot().stream()
                .filter(m -> !m.isPrivate())
                .filter(predicate)
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .sorted(tieBreak)
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
