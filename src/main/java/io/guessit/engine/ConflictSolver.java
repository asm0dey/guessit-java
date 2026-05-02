package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

public final class ConflictSolver {
    private ConflictSolver() {}

    public static void solve(MatchSet matches) {
        var all = matches.all().toList();
        // Comparator: priority desc, length desc, start asc, name asc (stable tiebreak)
        var cmp = Comparator
            .comparingInt((Match m) -> m.priority()).reversed()
            .thenComparing(Comparator.comparingInt(Match::length).reversed())
            .thenComparingInt(Match::start)
            .thenComparing(Match::name);
        var sorted = new ArrayList<>(all);
        sorted.sort(cmp);
        var kept = new ArrayList<Match>();
        var dropped = new HashSet<Match>();
        for (var candidate : sorted) {
            boolean dropThis = false;
            for (var winner : kept) {
                if (candidate.overlaps(winner)
                        && !candidate.tags().contains("coexist")
                        && !winner.tags().contains("coexist")) {
                    dropThis = true;
                    break;
                }
            }
            if (dropThis) dropped.add(candidate);
            else kept.add(candidate);
        }
        for (var d : dropped) matches.remove(d);
    }
}
