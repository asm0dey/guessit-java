package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MatchSetTest {

    @Test
    void addAndAll() {
        var s = new MatchSet();
        s.add(Match.of("year", 2020, 0, 4, "2020"));
        s.add(Match.of("source", "BluRay", 5, 11, "BluRay"));
        assertEquals(2, s.all().count());
    }

    @Test
    void namedFilter() {
        var s = new MatchSet();
        s.add(Match.of("year", 2020, 0, 4, "2020"));
        s.add(Match.of("source", "BluRay", 5, 11, "BluRay"));
        var years = s.named("year").collect(Collectors.toList());
        assertEquals(1, years.size());
        assertEquals(2020, years.get(0).value());
    }

    @Test
    void overlapping() {
        var s = new MatchSet();
        var a = Match.of("year", 2020, 0, 4, "2020");
        var b = Match.of("season", 20, 1, 3, "20");
        s.add(a); s.add(b);
        var overs = s.overlapping(0, 5).collect(Collectors.toList());
        assertEquals(2, overs.size());
        var nonOver = s.overlapping(10, 20).collect(Collectors.toList());
        assertTrue(nonOver.isEmpty());
    }

    @Test
    void inMarker() {
        var s = new MatchSet();
        var marker = new Marker("path", 0, 10, "abcdefghij");
        s.add(Match.of("year", 2020, 0, 4, "2020"));
        s.add(Match.of("year", 1999, 12, 16, "1999"));
        var inside = s.inMarker(marker).collect(Collectors.toList());
        assertEquals(1, inside.size());
        assertEquals(2020, inside.get(0).value());
    }

    @Test
    void removeAndReplace() {
        var s = new MatchSet();
        var a = Match.of("year", 2020, 0, 4, "2020");
        var b = Match.of("year", 1999, 0, 4, "1999");
        s.add(a);
        s.replace(a, b);
        assertEquals(List.of(b), s.all().collect(Collectors.toList()));
        s.remove(b);
        assertEquals(0, s.all().count());
    }
}
