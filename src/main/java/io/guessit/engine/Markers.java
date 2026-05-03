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
        var indexed = new ArrayList<int[]>();
        for (var i = 0; i < paths.size(); i++) {
            var p = paths.get(i);
            var count = (int) matches.all().filter(x -> p.covers(x.start(), x.end())).count();
            indexed.add(new int[]{i, count});
        }
        indexed.sort((a, b) -> {
            var byCount = Integer.compare(b[1], a[1]);
            return byCount != 0 ? byCount : Integer.compare(a[0], b[0]);
        });
        var ret = new ArrayList<Marker>();
        for (var entry : indexed) ret.add(paths.get(entry[0]));
        return ret;
    }
}
