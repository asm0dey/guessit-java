package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeasonEpisodeExtractorTest {
    @Test void s01e02() {
        var r = Guessit.parse("Show.S01E02.mkv");
        assertEquals(1, r.season());
        assertEquals(2, r.episode());
    }
    @Test void multiEpisode_S01E02E03() {
        var r = Guessit.parse("Show.S01E02E03.mkv");
        assertEquals(1, r.season());
        assertEquals(List.of(2, 3), r.episodeList());
    }
    @Test void shortForm_01x02() {
        var r = Guessit.parse("Show.01x02.HDTV.mkv");
        assertEquals(1, r.season());
        assertEquals(2, r.episode());
    }
    @Test void multiSeason_S01S02S03() {
        var r = Guessit.parse("Show.S01S02S03.Pack.mkv");
        assertEquals(List.of(1, 2, 3), r.seasonList());
    }
    @Test void rangeDash_S01E02_E04() {
        var result = Guessit.parse("Show.S01E02-04.mkv");
        System.err.println("DEBUG result: " + result);
        assertEquals(1, result.season());
        assertEquals(List.of(2, 3, 4), result.episodeList());
    }
    @Test void capPattern_Cap_102() {
        var r = Guessit.parse("Show.Cap.102.HDTV.mkv");
        assertEquals(1, r.season());
        assertEquals(2, r.episode());
    }
    @Test void seasonOnly_S01() {
        var r = Guessit.parse("Show.S01.HDTV.mkv");
        assertEquals(1, r.season());
        assertNull(r.episode());
    }
}
