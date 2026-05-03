package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class Markers {
    private Markers() {}

    public static Stream<Marker> named(List<Marker> markers, String name) {
        return markers.stream().filter(m -> m.name().equals(name));
    }

    public static Stream<Marker> coveringMatch(List<Marker> markers, Match m, Predicate<Marker> p) {
        return markers.stream()
            .filter(mk -> mk.covers(m.start(), m.end()))
            .filter(p)
            .sorted(Comparator.comparingInt(Marker::start));
    }

    public static Optional<Marker> atMatch(List<Marker> markers, Match m, Predicate<Marker> p) {
        return coveringMatch(markers, m, p).findFirst();
    }

    public static List<Marker> markerSorted(List<Marker> paths, MatchSet matches) {
        var indexed = new ArrayList<Integer>();
        for (var i = 0; i < paths.size(); i++) indexed.add(i);

        // exclusion predicate matching Python's marker_comparator_predicate
        java.util.function.Predicate<Match> predicate = m ->
            !m.isPrivate()
            && !m.name().equals("proper_count")
            && !m.name().equals("title")
            && !(m.name().equals("container") && m.tags().contains("extension"))
            && !(m.name().equals("other") && "Rip".equals(m.value()));

        indexed.sort((a, b) -> {
            var pa = paths.get(a);
            var pb = paths.get(b);
            // unique match names for each marker
            var wa = (int) matches.range(pa.start(), pa.end(), predicate)
                .map(Match::name).distinct().count();
            var wb = (int) matches.range(pb.start(), pb.end(), predicate)
                .map(Match::name).distinct().count();
            var byWeight = Integer.compare(wb, wa);
            if (byWeight != 0) return byWeight;
            // tiebreaker: rightmost path first (reverse index)
            return Integer.compare(b, a);
        });
        var ret = new ArrayList<Marker>();
        for (var entry : indexed) ret.add(paths.get(entry));
        return ret;
    }
}
