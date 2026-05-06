package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EpisodeFormatExtractorTest {
    @Test void minisode() {
        var r = Guessit.parse("Show.S01E02.Minisode.mkv");
        assertEquals("Minisode", r.episodeFormat());
    }
    @Test void minisodesPlural() {
        var r = Guessit.parse("Show.S01.Minisodes.Pack.mkv");
        assertEquals("Minisode", r.episodeFormat());
    }
    @Test void noFormat() {
        var r = Guessit.parse("Show.S01E02.mkv");
        assertNull(r.episodeFormat());
    }
}
