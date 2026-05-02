package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeDetailsExtractorTest {
    @Test void specialNextToEpisode() {
        var r = Guessit.parse("Show.S01E02.Special.mkv").toMap();
        assertEquals("Special", r.get("episode_details"));
    }
    @Test void pilotStandalone() {
        var r = Guessit.parse("Show.S01E01.Pilot.mkv").toMap();
        assertEquals("Pilot", r.get("episode_details"));
    }
    @Test void detachedPilotIsDropped() {
        var r = Guessit.parse("PilotXFilesShow.mkv").toMap();
        assertNull(r.get("episode_details"));
    }
    @Test void multipleDetails() {
        var r = Guessit.parse("Show.S01E02.Special.Final.mkv").toMap();
        var v = r.get("episode_details");
        assertTrue(v instanceof java.util.List<?> l && l.size() == 2 && l.contains("Special") && l.contains("Final"));
    }
}
