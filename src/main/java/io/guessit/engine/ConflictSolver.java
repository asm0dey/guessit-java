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

    private static final String TAG_COEXIST = "coexist";
    private static final String REASON_SHORTER_SPAN = "shorter span";
    private static final String REASON_LOWER_PRIORITY = "lower priority";

    private ConflictSolver() {}

    public static void solve(MatchSet matches) { solve(matches, Trace.NOOP); }

    public static void solve(MatchSet matches, Trace trace) {
        var publicMatches = matches.all()
                .filter(m -> !m.isPrivate())
                .sorted(Comparator.comparingInt(Match::length))
                .toList();

        var toRemove = new HashSet<Match>();

        for (var match : publicMatches) {
            if (!toRemove.contains(match)) {
                resolveAgainstConflicts(match, publicMatches, toRemove, trace);
            }
        }

        matches.removeAll(toRemove);
    }

    private static void resolveAgainstConflicts(Match match, List<Match> publicMatches, Set<Match> toRemove, Trace trace) {
        if (match.tags().contains(TAG_COEXIST)) return;

        var conflicting = findConflicting(match, publicMatches, toRemove);
        boolean removalRecorded = false;

        for (int i = 0; i < conflicting.size() && !removalRecorded; i++) {
            var conflictingMatch = conflicting.get(i);

            if (!conflictingMatch.tags().contains(TAG_COEXIST)) {
                var removed = defaultConflictSolver(match, conflictingMatch);

                removalRecorded = recordRemoval(match, conflictingMatch, removed, toRemove, trace);
            }
        }
    }

    private static boolean recordRemoval(Match match, Match conflictingMatch, Match removed,
                                         Set<Match> toRemove, Trace trace) {
        if (removed == null || toRemove.contains(removed)) return false;

        var toKeep = (removed == match) ? conflictingMatch : match;

        if (!toRemove.contains(toKeep)) {
            toRemove.add(removed);
            String reason = removed.length() < toKeep.length() ? REASON_SHORTER_SPAN : REASON_LOWER_PRIORITY;
            trace.subStep("Dropping " + summary(removed) + " — overlaps " + summary(toKeep) + " (" + reason + ")");
        }

        return true;
    }

    private static String summary(Match m) {
        return m.name().name().toLowerCase(Locale.ROOT) + " '" + m.raw() + "' at " + m.start() + "-" + m.end();
    }

    private static Match defaultConflictSolver(Match match, Match conflictingMatch) {
        int matchLen = match.length();
        int conflictingLen = conflictingMatch.length();

        if (conflictingLen < matchLen) return conflictingMatch;
        if (matchLen < conflictingLen) return match;

        if (match.priority() > conflictingMatch.priority()) return conflictingMatch;
        if (match.priority() < conflictingMatch.priority()) return match;

        return null;
    }

    private static List<Match> findConflicting(Match match, List<Match> publicMatches, Set<Match> toRemove) {
        return publicMatches.stream()
                .filter(other -> other != match)
                .filter(other -> !toRemove.contains(other))
                .filter(match::overlaps)
                .sorted(Comparator.comparingInt(Match::length))
                .toList();
    }
}