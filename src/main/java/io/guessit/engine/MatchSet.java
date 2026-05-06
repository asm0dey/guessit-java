package io.guessit.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Mutable collection of {@link Match}es shared by every phase via
 * {@link ParseContext#matches}.
 *
 * <p>Backed by a plain {@link ArrayList}; insertion order is preserved but not
 * relied on by callers — query helpers ({@link #named}, {@link #overlapping},
 * {@link #inMarker}) hide ordering. Streams returned by the helpers are live
 * over the current snapshot, so collect to a list before mutating.
 */
public final class MatchSet {
    private final List<Match> matches = new ArrayList<>();

    public void add(Match m) { matches.add(m); }

    public boolean remove(Match m) { return matches.remove(m); }

    /**
     * Bulk remove. O(n) total instead of O(n*k) for n elements, k removals.
     * Membership is tested by {@link Match#equals}, identical to {@link #remove}.
     */
    public boolean removeAll(Collection<Match> toRemove) {
        if (toRemove.isEmpty()) return false;
        var set = (toRemove instanceof HashSet<Match> hs) ? hs : new HashSet<>(toRemove);
        return matches.removeIf(set::contains);
    }

    /**
     * Replaces an existing match in place, preserving insertion position so
     * callers iterating by index aren't perturbed.
     *
     * @throws IllegalArgumentException if {@code oldMatch} is not present
     */
    public void replace(Match oldMatch, Match newMatch) {
        var idx = matches.indexOf(oldMatch);
        if (idx < 0) throw new IllegalArgumentException("Match not present: " + oldMatch);
        matches.set(idx, newMatch);
    }

    public Stream<Match> all() { return matches.stream(); }

    /** All matches whose {@link Match#name()} equals {@code name}. */
    public Stream<Match> named(MatchName matchName) { return matches.stream().filter(m -> m.name().equals(matchName)); }

    /** All matches whose span overlaps the half-open range {@code [start, end)}. */
    public Stream<Match> overlapping(int start, int end) {
        return matches.stream().filter(m -> m.start() < end && start < m.end());
    }

    /** All matches whose span lies entirely inside {@code marker}. */
    public Stream<Match> inMarker(Marker marker) {
        return matches.stream().filter(m -> marker.covers(m.start(), m.end()));
    }

    public Stream<Match> range(int start, int end, Predicate<Match> p) {
        return matches.stream()
            .filter(m -> m.start() >= start && m.end() <= end)
            .filter(p);
    }

    public Optional<Match> previous(Match m, Predicate<Match> p) {
        return matches.stream()
            .filter(o -> o.end() <= m.start())
            .filter(p)
            .max(Comparator.comparingInt(Match::end));
    }

    public Optional<Match> next(Match m, Predicate<Match> p) {
        return matches.stream()
            .filter(o -> o.start() >= m.end())
            .filter(p)
            .min(Comparator.comparingInt(Match::start));
    }

    public Optional<Match> chainBefore(int pos, String input, String seps, Predicate<Match> p) {
        // Mirror python rebulk's chain_before: walk backward; at each position
        // gather matches at that index, stop when neither a predicate-matching
        // match nor a separator character is present.
        Match found = null;
        for (int i = pos - 1; i >= 0; i--) {
            final int idx = i;
            var matchesAtIdx = matches.stream()
                .filter(m -> m.start() <= idx && idx < m.end())
                .filter(p)
                .findFirst();
            if (matchesAtIdx.isPresent()) {
                if (found == null) found = matchesAtIdx.get();
                continue;
            }
            // No match at this index — only continue if separator char.
            if (seps.indexOf(input.charAt(i)) < 0) break;
        }
        return Optional.ofNullable(found);
    }

    public Optional<Match> chainAfter(int pos, String input, String seps, Predicate<Match> p) {
        Match found = null;
        for (int i = pos; i < input.length(); i++) {
            final int idx = i;
            var matchesAtIdx = matches.stream()
                .filter(m -> m.start() <= idx && idx < m.end())
                .filter(p)
                .findFirst();
            if (matchesAtIdx.isPresent()) {
                if (found == null) found = matchesAtIdx.get();
                continue;
            }
            if (seps.indexOf(input.charAt(i)) < 0) break;
        }
        return Optional.ofNullable(found);
    }

    public Stream<Match> tagged(String tag) {
        return matches.stream().filter(m -> m.tags().contains(tag));
    }

    public List<Match> snapshot() { return List.copyOf(matches); }

}
