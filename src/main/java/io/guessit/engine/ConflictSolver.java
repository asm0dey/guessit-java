package io.guessit.engine;

import java.util.*;

public final class ConflictSolver {
    private ConflictSolver() {}

    /**
     * Replicates Python's ConflictSolver processor with _default_conflict_solver.
     *
     * Python logic:
     * - Sort all non-private matches by span length ascending (shortest first).
     * - For each short match, find all conflicting matches (any position overlap).
     * - For each pair (short, long): if short.initiator.length < long.initiator.length
     *   → drop short. If long.initiator.length < short.initiator.length → drop long.
     *   Else (equal lengths) → keep both.
     * - For top-level matches, initiator == match itself, so initiator.length == match span length.
     */
    public static void solve(MatchSet matches) {
        var publicMatches = matches.all()
            .filter(m -> !m.isPrivate())
            .sorted(Comparator.comparingInt(Match::length))
            .toList();

        var toRemove = new HashSet<Match>();

        for (var match : publicMatches) {
            if (toRemove.contains(match)) continue;
            var conflicting = findConflicting(matches, match, publicMatches, toRemove);

            // Sort conflicting by span length ascending (Python: conflicting_matches.sort(key=len))
            conflicting.sort(Comparator.comparingInt(Match::length));

            for (var conflictingMatch : conflicting) {
                if (match.tags().contains("coexist") || conflictingMatch.tags().contains("coexist")) continue;
                var removed = defaultConflictSolver(match, conflictingMatch);
                if (removed != null && !toRemove.contains(removed)) {
                    // Determine which is the keeper
                    var toKeep = (removed == match) ? conflictingMatch : match;
                    if (!toRemove.contains(toKeep)) {
                        toRemove.add(removed);
                    }
                    break;
                }
            }
        }

        for (var d : toRemove) matches.remove(d);
    }

    /**
     * Python's _default_conflict_solver: compares initiator (i.e., match span for top-level) lengths.
     * Falls back to priority (higher wins) when lengths are equal.
     * Returns the match to remove, or null if both should be kept.
     */
    private static Match defaultConflictSolver(Match match, Match conflictingMatch) {
        // For top-level matches, initiator == match itself, so len(initiator) == len(match)
        int matchLen = match.length();
        int conflictingLen = conflictingMatch.length();

        if (conflictingLen < matchLen) return conflictingMatch;
        if (matchLen < conflictingLen) return match;
        // equal lengths → higher priority wins
        if (match.priority() > conflictingMatch.priority()) return conflictingMatch;
        if (match.priority() < conflictingMatch.priority()) return match;
        return null; // same priority → keep both
    }

    /**
     * Find matches that overlap with `match`, are not private, and are not already marked for removal.
     */
    private static List<Match> findConflicting(MatchSet matches, Match match,
                                                List<Match> publicMatches, Set<Match> toRemove) {
        var result = new ArrayList<Match>();
        for (var other : publicMatches) {
            if (other == match) continue;
            if (toRemove.contains(other)) continue;
            if (other.isPrivate()) continue;
            if (match.overlaps(other)) {
                result.add(other);
            }
        }
        return result;
    }
}
