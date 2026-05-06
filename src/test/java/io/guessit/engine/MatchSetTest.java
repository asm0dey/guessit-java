package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static io.guessit.engine.MatchName.*;

class MatchSetTest {

    @Test
    void addAndAll() {
        var s = new MatchSet();
        s.add(Match.of(YEAR, 2020, 0, 4, "2020"));
        s.add(Match.of(SOURCE, "BluRay", 5, 11, "BluRay"));
        assertEquals(2, s.all().count());
    }

    @Test
    void namedFilter() {
        var s = new MatchSet();
        s.add(Match.of(YEAR, 2020, 0, 4, "2020"));
        s.add(Match.of(SOURCE, "BluRay", 5, 11, "BluRay"));
        var years = s.named(YEAR).toList();
        assertEquals(1, years.size());
        assertEquals(2020, years.getFirst().value());
    }

    @Test
    void overlapping() {
        var s = new MatchSet();
        var a = Match.of(YEAR, 2020, 0, 4, "2020");
        var b = Match.of(SEASON, 20, 1, 3, "20");
        s.add(a); s.add(b);
        var overs = s.overlapping(0, 5).toList();
        assertEquals(2, overs.size());
        var nonOver = s.overlapping(10, 20).toList();
        assertTrue(nonOver.isEmpty());
    }

    @Test
    void inMarker() {
        var s = new MatchSet();
        var marker = new Marker("path", 0, 10, "abcdefghij");
        s.add(Match.of(YEAR, 2020, 0, 4, "2020"));
        s.add(Match.of(YEAR, 1999, 12, 16, "1999"));
        var inside = s.inMarker(marker).toList();
        assertEquals(1, inside.size());
        assertEquals(2020, inside.getFirst().value());
    }

    @Test
    void removeAndReplace() {
        var s = new MatchSet();
        var a = Match.of(YEAR, 2020, 0, 4, "2020");
        var b = Match.of(YEAR, 1999, 0, 4, "1999");
        s.add(a);
        s.replace(a, b);
        assertEquals(List.of(b), s.all().toList());
        s.remove(b);
        assertEquals(0, s.all().count());
    }

    @Test
    void rangeReturnsMatchesFullyInsideSpan() {
        var set = new MatchSet();
        set.add(Match.of(MatchName.OTHER, 1, 0, 5, "00000"));
        set.add(Match.of(MatchName.OTHER, 2, 6, 10, "1111"));
        set.add(Match.of(MatchName.OTHER, 3, 11, 15, "2222"));
        var inRange = set.range(0, 10, _ -> true).toList();
        assertEquals(2, inRange.size());
        assertEquals(MatchName.OTHER, inRange.get(0).name());
        assertEquals(MatchName.OTHER, inRange.get(1).name());
    }

    @Test
    void previousAndNextRespectPredicate() {
        var set = new MatchSet();
        var a = Match.of(MatchName.OTHER, 1, 0, 3, "aaa");
        var b = Match.of(MatchName.OTHER, 2, 5, 8, "bbb");
        var c = Match.of(MatchName.OTHER, 3, 10, 13, "ccc");
        set.add(a); set.add(b); set.add(c);
        assertEquals(a, set.previous(b, m -> true).orElseThrow());
        assertEquals(c, set.next(b, m -> true).orElseThrow());
        assertTrue(set.previous(a, m -> true).isEmpty());
    }

    @Test
    void chainBeforeWalksOnlyThroughSeps() {
        var input = "abc.def-ghi";
        var set = new MatchSet();
        var a = Match.of(MatchName.OTHER, 1, 0, 3, "abc");
        var b = Match.of(MatchName.OTHER, 2, 4, 7, "def");
        set.add(a); set.add(b);
        assertEquals(b, set.chainBefore(8, input, " ._-", m -> true).orElseThrow());
        assertEquals(a, set.chainBefore(4, input, " ._-", m -> true).orElseThrow());
    }

    @Test
    void chainAfterWalksOnlyThroughSeps() {
        var input = "abc.def-ghi";
        var set = new MatchSet();
        var b = Match.of(MatchName.OTHER, 2, 4, 7, "def");
        var c = Match.of(MatchName.OTHER, 3, 8, 11, "ghi");
        set.add(b); set.add(c);
        assertEquals(b, set.chainAfter(3, input, " ._-", m -> true).orElseThrow());
        assertEquals(c, set.chainAfter(7, input, " ._-", m -> true).orElseThrow());
    }

    @Test
    void taggedFiltersByTagSet() {
        var set = new MatchSet();
        set.add(new Match(MatchName.OTHER, null, 0, 1, "a", 1000, java.util.Set.of("foo"), false));
        set.add(Match.of(MatchName.OTHER, null, 2, 3, "b"));
        var tagged = set.tagged("foo").toList();
        assertEquals(1, tagged.size());
        assertEquals(MatchName.OTHER, tagged.getFirst().name());
    }
}
