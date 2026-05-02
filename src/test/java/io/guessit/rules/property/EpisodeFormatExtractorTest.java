package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeFormatExtractorTest {
    @Test void minisode() {
        var r = Guessit.parse("Show.S01E02.Minisode.mkv").toMap();
        assertEquals("Minisode", r.get("episode_format"));
    }
    @Test void minisodesPlural() {
        var r = Guessit.parse("Show.S01.Minisodes.Pack.mkv").toMap();
        assertEquals("Minisode", r.get("episode_format"));
    }
    @Test void noFormat() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("episode_format"));
    }
}
