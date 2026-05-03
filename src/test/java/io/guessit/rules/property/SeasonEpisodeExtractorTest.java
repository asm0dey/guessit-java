package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeasonEpisodeExtractorTest {
    @Test void s01e02() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void multiEpisode_S01E02E03() {
        var r = Guessit.parse("Show.S01E02E03.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(List.of(2, 3), r.get("episode"));
    }
    @Test void shortForm_01x02() {
        var r = Guessit.parse("Show.01x02.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void multiSeason_S01S02S03() {
        var r = Guessit.parse("Show.S01S02S03.Pack.mkv").toMap();
        assertEquals(List.of(1, 2, 3), r.get("season"));
    }
    @Test void rangeDash_S01E02_E04() {
        var result = Guessit.parse("Show.S01E02-04.mkv");
        var r = result.toMap();
        System.err.println("DEBUG rangeDash: " + r);
        System.err.println("DEBUG result: " + result);
        assertEquals(1, r.get("season"));
        assertEquals(List.of(2, 3, 4), r.get("episode"));
    }
    @Test void capPattern_Cap_102() {
        var r = Guessit.parse("Show.Cap.102.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void seasonOnly_S01() {
        var r = Guessit.parse("Show.S01.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertNull(r.get("episode"));
    }
}
