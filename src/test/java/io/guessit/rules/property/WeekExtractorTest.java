package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeekExtractorTest {
    @Test void weekWordWithNumber() {
        var r = Guessit.parse("Show.Week.12.HDTV.mkv").toMap();
        assertEquals(12, r.get("week"));
    }
    @Test void invalidWeekRangeDropped() {
        // Week 99 is out of valid range (1-52)
        var r = Guessit.parse("Show.Week.99.HDTV.mkv").toMap();
        assertNull(r.get("week"));
    }
    @Test void noWeek() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("week"));
    }
}
