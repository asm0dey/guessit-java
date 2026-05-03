package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
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
    public Stream<Match> named(String name) { return matches.stream().filter(m -> m.name().equals(name)); }

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
        var i = pos - 1;
        while (i >= 0 && seps.indexOf(input.charAt(i)) >= 0) i--;
        if (i < 0) return Optional.empty();
        var endPos = i + 1;
        return matches.stream().filter(m -> m.end() == endPos).filter(p).findFirst();
    }

    public Optional<Match> chainAfter(int pos, String input, String seps, Predicate<Match> p) {
        var i = pos;
        while (i < input.length() && seps.indexOf(input.charAt(i)) >= 0) i++;
        if (i >= input.length()) return Optional.empty();
        var startPos = i;
        return matches.stream().filter(m -> m.start() == startPos).filter(p).findFirst();
    }

    public Stream<Match> tagged(String tag) {
        return matches.stream().filter(m -> m.tags().contains(tag));
    }

    public List<Match> snapshot() { return List.copyOf(matches); }

    public int size() { return matches.size(); }
}
