package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionExtractorTest {
    @Test void versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv").toMap();
        assertEquals(2, r.get("version"));
    }
    @Test void detachedVersionNoEpisodeIsDropped() {
        var r = Guessit.parse("v3 randomshow.mkv").toMap();
        assertEquals(3, r.get("version"));
    }
    @Test void noVersion() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("version"));
    }
}
