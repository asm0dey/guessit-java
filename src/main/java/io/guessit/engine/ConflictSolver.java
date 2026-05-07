package io.guessit.engine;

import java.util.*;

/**
 * Overlap resolver invoked by {@link ConflictPhase}.
 *
 * <p>Mirrors Python guessit's {@code ConflictSolver} processor. Compared to a
 * naive "longest wins" sweep, the shortest-first iteration order matters:
 * removing a short match early can free up overlap groups that contained it,
 * affecting later decisions. The {@code "coexist"} tag opts a match out of
 * the comparison entirely (it stays even when something longer overlaps it).
 */
public final class ConflictSolver {
    private ConflictSolver() {}

    /**
     * Replicates Python's ConflictSolver processor with _default_conflict_solver.
     * <p>
     * Python logic:
     * - Sort all non-private matches by span length ascending (shortest first).
     * - For each short match, find all conflicting matches (any position overlap).
     * - For each pair (short, long): if short.initiator.length < long.initiator.length
     *   → drop short. If long.initiator.length < short.initiator.length → drop long.
     *   Else (equal lengths) → keep both.
     * - For top-level matches, initiator == match itself, so initiator.length == match span length.
     *
     * <p>Backwards-compatible no-trace overload delegates to {@link #solve(MatchSet, Trace)} with {@link Trace#NOOP}.
     */
    public static void solve(MatchSet matches) { solve(matches, Trace.NOOP); }

    public static void solve(MatchSet matches, Trace trace) {
        var publicMatches = matches.all()
            .filter(m -> !m.isPrivate())
            .sorted(Comparator.comparingInt(Match::length))
            .toList();
        var toRemove = new HashSet<Match>();
        for (var match : publicMatches) {
            if (toRemove.contains(match)) continue;
            resolveAgainstConflicts(match, publicMatches, toRemove, trace);
        }
        matches.removeAll(toRemove);
    }

    private static void resolveAgainstConflicts(Match match, List<Match> publicMatches, Set<Match> toRemove, Trace trace) {
        var conflicting = findConflicting(match, publicMatches, toRemove);
        // Python: conflicting_matches.sort(key=len) — span length ascending
        conflicting.sort(Comparator.comparingInt(Match::length));
        for (var conflictingMatch : conflicting) {
            if (match.tags().contains("coexist") || conflictingMatch.tags().contains("coexist")) continue;
            var removed = defaultConflictSolver(match, conflictingMatch);
            if (recordRemoval(match, conflictingMatch, removed, toRemove, trace)) break;
        }
    }

    /** Records the loser's removal when both keeper and loser are still alive.
     *  Returns true to break the per-match loop after a decision is made. */
    private static boolean recordRemoval(Match match, Match conflictingMatch, Match removed,
                                         Set<Match> toRemove, Trace trace) {
        if (removed == null || toRemove.contains(removed)) return false;
        var toKeep = (removed == match) ? conflictingMatch : match;
        if (!toRemove.contains(toKeep)) {
            toRemove.add(removed);
            String reason = removed.length() < toKeep.length() ? "shorter span" : "lower priority";
            trace.subStep("Dropping " + summary(removed) + " — overlaps " + summary(toKeep) + " (" + reason + ")");
        }
        return true;
    }

    private static String summary(Match m) {
        return m.name().name().toLowerCase(Locale.ROOT) + " '" + m.raw() + "' at " + m.start() + "-" + m.end();
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
    private static List<Match> findConflicting(Match match,
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
