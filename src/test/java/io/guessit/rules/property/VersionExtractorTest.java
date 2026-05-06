package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VersionExtractorTest {
    @Test void versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv");
        assertEquals(2, r.version());
    }
    @Test void detachedVersionNoEpisodeIsDropped() {
        var r = Guessit.parse("v3 randomshow.mkv");
        assertEquals(3, r.version());
    }
    @Test void noVersion() {
        var r = Guessit.parse("Show.S01E02.mkv");
        assertNull(r.version());
    }
}
