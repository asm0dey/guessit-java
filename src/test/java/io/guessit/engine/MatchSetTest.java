package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.guessit.engine.Match.of;
import static io.guessit.engine.MatchName.*;
import static org.assertj.core.api.Assertions.assertThat;

class MatchSetTest {

    @Test
    void addAndAll() {
        var s = new MatchSet();
        s.add(of(YEAR, 2020, 0, 4, "2020"));
        s.add(of(SOURCE, "BluRay", 5, 11, "BluRay"));
        assertThat(s.all().count()).isEqualTo(2);
    }

    @Test
    void namedFilter() {
        var s = new MatchSet();
        s.add(of(YEAR, 2020, 0, 4, "2020"));
        s.add(of(SOURCE, "BluRay", 5, 11, "BluRay"));
        var years = s.named(YEAR).toList();
        assertThat(years).hasSize(1);
        assertThat(years.getFirst().value()).isEqualTo(2020);
    }

    @Test
    void overlapping() {
        var s = new MatchSet();
        var a = of(YEAR, 2020, 0, 4, "2020");
        var b = of(SEASON, 20, 1, 3, "20");
        s.add(a);
        s.add(b);
        var overs = s.overlapping(0, 5).toList();
        assertThat(overs).hasSize(2);
        var nonOver = s.overlapping(10, 20).toList();
        assertThat(nonOver).isEmpty();
    }

    @Test
    void inMarker() {
        var s = new MatchSet();
        var marker = new Marker("path", 0, 10, "abcdefghij");
        s.add(of(YEAR, 2020, 0, 4, "2020"));
        s.add(of(YEAR, 1999, 12, 16, "1999"));
        var inside = s.inMarker(marker).toList();
        assertThat(inside).hasSize(1);
        assertThat(inside.getFirst().value()).isEqualTo(2020);
    }

    @Test
    void removeAndReplace() {
        var s = new MatchSet();
        var a = of(YEAR, 2020, 0, 4, "2020");
        var b = of(YEAR, 1999, 0, 4, "1999");
        s.add(a);
        s.replace(a, b);
        assertThat(s.all().toList()).isEqualTo(List.of(b));
        s.remove(b);
        assertThat(s.all().count()).isZero();
    }

    @Test
    void rangeReturnsMatchesFullyInsideSpan() {
        var set = new MatchSet();
        set.add(of(OTHER, 1, 0, 5, "00000"));
        set.add(of(OTHER, 2, 6, 10, "1111"));
        set.add(of(OTHER, 3, 11, 15, "2222"));
        var inRange = set.range(0, 10, _ -> true).toList();
        assertThat(inRange).hasSize(2);
        assertThat(inRange.get(0).name()).isEqualTo(OTHER);
        assertThat(inRange.get(1).name()).isEqualTo(OTHER);
    }

    @Test
    void previousAndNextRespectPredicate() {
        var set = new MatchSet();
        var a = of(OTHER, 1, 0, 3, "aaa");
        var b = of(OTHER, 2, 5, 8, "bbb");
        var c = of(OTHER, 3, 10, 13, "ccc");
        set.add(a);
        set.add(b);
        set.add(c);
        assertThat(set.previous(b, m -> true).orElseThrow()).isEqualTo(a);
        assertThat(set.next(b, m -> true).orElseThrow()).isEqualTo(c);
        assertThat(set.previous(a, m -> true)).isEmpty();
    }

    @Test
    void chainBeforeWalksOnlyThroughSeps() {
        var input = "abc.def-ghi";
        var set = new MatchSet();
        var a = of(OTHER, 1, 0, 3, "abc");
        var b = of(OTHER, 2, 4, 7, "def");
        set.add(a);
        set.add(b);
        assertThat(set.chainBefore(8, input, " ._-", m -> true).orElseThrow()).isEqualTo(b);
        assertThat(set.chainBefore(4, input, " ._-", m -> true).orElseThrow()).isEqualTo(a);
    }

    @Test
    void chainAfterWalksOnlyThroughSeps() {
        var input = "abc.def-ghi";
        var set = new MatchSet();
        var b = of(OTHER, 2, 4, 7, "def");
        var c = of(OTHER, 3, 8, 11, "ghi");
        set.add(b);
        set.add(c);
        assertThat(set.chainAfter(3, input, " ._-", m -> true).orElseThrow()).isEqualTo(b);
        assertThat(set.chainAfter(7, input, " ._-", m -> true).orElseThrow()).isEqualTo(c);
    }

    @Test
    void taggedFiltersByTagSet() {
        var set = new MatchSet();
        set.add(new Match(OTHER, null, 0, 1, "a", 1000, Set.of("foo"), false));
        set.add(of(OTHER, null, 2, 3, "b"));
        var tagged = set.tagged("foo").toList();
        assertThat(tagged).hasSize(1);
        assertThat(tagged.getFirst().name()).isEqualTo(OTHER);
    }
}
