package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainTest {
    @Test void singleHeadSingleTail() {
        var head = Pattern.compile("(?i)s(?<season>\\d+)e(?<episode>\\d+)");
        var tail = Pattern.compile("(?i)-?e(?<episode>\\d+)");
        var runs = new Chain(head).tail(tail, Chain.Repeater.STAR).scan("S01E02-E03");
        assertEquals(1, runs.size());
        var run = runs.get(0);
        assertEquals(0, run.start());
        assertEquals(10, run.end());
        assertEquals(List.of("01"), run.captures("season"));
        assertEquals(List.of("02", "03"), run.captures("episode"));
    }
    @Test void plusRequiresAtLeastOneTail() {
        var head = Pattern.compile("(?i)(?<season>\\d+)x(?<episode>\\d+)");
        var tail = Pattern.compile("(?i)\\s+(?<season>\\d+)x(?<episode>\\d+)");
        assertTrue(new Chain(head).tail(tail, Chain.Repeater.PLUS).scan("01x02").isEmpty());
        assertEquals(1, new Chain(head).tail(tail, Chain.Repeater.PLUS).scan("01x02 03x04").size());
    }
    @Test void noOverlap() {
        var head = Pattern.compile("\\d");
        var runs = new Chain(head).scan("abc1def2ghi");
        assertEquals(2, runs.size());
        assertEquals(3, runs.get(0).start());
        assertEquals(7, runs.get(1).start());
    }
}
