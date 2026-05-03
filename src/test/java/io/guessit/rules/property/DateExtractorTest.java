package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateExtractorTest {
    @Test void ymdDate() {
        var r = Guessit.parse("Show.2002-04-22.HDTV.mkv").toMap();
        assertEquals(LocalDate.of(2002, 4, 22), r.get("date"));
    }
    @Test void dmyDate() {
        var r = Guessit.parse("Show.17-06-1998.HDTV.mkv").toMap();
        assertEquals(LocalDate.of(1998, 6, 17), r.get("date"));
    }
    @Test void noDate() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("date"));
    }
    @Test void dateSuppressesEpisode() {
        var r = Guessit.parse("Show.2002-04-22.S01E02.mkv").toMap();
        // date should be present; S01E02 uses clear SxxExx which is outside the date span,
        // so episode should survive. The suppression applies to bare numbers inside the date span.
        assertEquals(LocalDate.of(2002, 4, 22), r.get("date"));
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void dateSuppressesYear() {
        var r = Guessit.parse("Show.2002-04-22.HDTV.mkv").toMap();
        assertEquals(LocalDate.of(2002, 4, 22), r.get("date"));
        assertNull(r.get("year"));
    }
}
