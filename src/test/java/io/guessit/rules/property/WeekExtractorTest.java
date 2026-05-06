package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeekExtractorTest {
    @Test void weekWordWithNumber() {
        var r = Guessit.parse("Show.Week.12.HDTV.mkv");
        assertEquals(12, r.extras() != null ? r.extras().get("week") : null);
    }
    @Test void invalidWeekRangeDropped() {
        // Week 99 is out of valid range (1-52)
        var r = Guessit.parse("Show.Week.99.HDTV.mkv");
        assertNull(r.extras() != null ? r.extras().get("week") : null);
    }
    @Test void noWeek() {
        var r = Guessit.parse("Show.S01E02.mkv");
        assertNull(r.extras() != null ? r.extras().get("week") : null);
    }
}
