package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class MatchSet {
    private final List<Match> matches = new ArrayList<>();

    public void add(Match m) { matches.add(m); }

    public boolean remove(Match m) { return matches.remove(m); }

    public void replace(Match oldMatch, Match newMatch) {
        var idx = matches.indexOf(oldMatch);
        if (idx < 0) throw new IllegalArgumentException("Match not present: " + oldMatch);
        matches.set(idx, newMatch);
    }

    public Stream<Match> all() { return matches.stream(); }

    public Stream<Match> named(String name) { return matches.stream().filter(m -> m.name().equals(name)); }

    public Stream<Match> overlapping(int start, int end) {
        return matches.stream().filter(m -> m.start() < end && start < m.end());
    }

    public Stream<Match> inMarker(Marker marker) {
        return matches.stream().filter(m -> marker.covers(m.start(), m.end()));
    }

    public int size() { return matches.size(); }
}
