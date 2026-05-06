package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.guessit.engine.MatchName.*;

class MarkersTest {
    @Test void namedFiltersByName() {
        var markers = List.of(
            new Marker("path", 0, 5, "12345"),
            new Marker("group", 1, 4, "234"),
            new Marker("path", 6, 10, "6789"));
        var paths = Markers.named(markers, "path").toList();
        assertEquals(2, paths.size());
    }
    @Test void atMatchReturnsContainingMarker() {
        var markers = List.of(new Marker("path", 0, 10, "0123456789"));
        var m = Match.of(G, null, 2, 5, "234");
        assertEquals(markers.getFirst(), Markers.atMatch(markers, m, mk -> true).orElseThrow());
    }
    @Test void markerSortedByDescendingMatchCount() {
        var p1 = new Marker("path", 0, 5, "0..4");
        var p2 = new Marker("path", 6, 12, "6..11");
        var matches = new MatchSet();
        matches.add(Match.of(ALTERNATIVE_TITLE, null, 6, 7, "a"));
        matches.add(Match.of(BONUS, null, 8, 9, "b"));
        matches.add(Match.of(COUNTRY, null, 10, 11, "c"));
        matches.add(Match.of(DATE, null, 0, 1, "d"));
        var sorted = Markers.markerSorted(List.of(p1, p2), matches);
        assertEquals(p2, sorted.get(0));
        assertEquals(p1, sorted.get(1));
    }
}
