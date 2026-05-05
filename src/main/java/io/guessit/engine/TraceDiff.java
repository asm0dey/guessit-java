package io.guessit.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Computes added / removed matches between two {@link MatchSet} snapshots and
 * forwards them to a {@link Trace}. Multiset-aware: if the same value-equal
 * {@link Match} appears N times in {@code before} and M times in {@code after},
 * {@code max(0, N-M)} removals and {@code max(0, M-N)} additions are emitted.
 *
 * <p>Removals are emitted before additions; additions preserve {@code after}
 * order; removals preserve {@code before} order.
 */
final class TraceDiff {
    private TraceDiff() {}

    static void emit(List<Match> before, List<Match> after, Trace trace) {
        var afterCounts = new HashMap<Match, Integer>();
        for (var m : after) afterCounts.merge(m, 1, Integer::sum);

        var removals = new ArrayList<Match>();
        for (var m : before) {
            var c = afterCounts.getOrDefault(m, 0);
            if (c == 0) {
                removals.add(m);
            } else {
                afterCounts.put(m, c - 1);
            }
        }
        for (var m : removals) trace.removed(m);

        var beforeCounts = new HashMap<Match, Integer>();
        for (var m : before) beforeCounts.merge(m, 1, Integer::sum);
        for (var m : after) {
            var c = beforeCounts.getOrDefault(m, 0);
            if (c == 0) {
                trace.added(m);
            } else {
                beforeCounts.put(m, c - 1);
            }
        }
    }
}
