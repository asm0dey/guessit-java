package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeDetailsExtractorTest {
    @Test void specialNextToEpisode() {
        var r = Guessit.parse("Show.S01E02.Special.mkv");
        var details = r.extras() != null ? r.extras().get("episode_details") : null;
        assertEquals("Special", details);
    }
    @Test void pilotStandalone() {
        var r = Guessit.parse("Show.S01E01.Pilot.mkv");
        var details = r.extras() != null ? r.extras().get("episode_details") : null;
        assertEquals("Pilot", details);
    }
    @Test void detachedPilotIsDropped() {
        var r = Guessit.parse("PilotXFilesShow.mkv");
        var details = r.extras() != null ? r.extras().get("episode_details") : null;
        assertNull(details);
    }
    @Test void multipleDetails() {
        var r = Guessit.parse("Show.S01E02.Special.Final.mkv");
        var details = r.extras() != null ? r.extras().get("episode_details") : null;
        assertTrue(details instanceof java.util.List<?> l && l.size() == 2 && l.contains("Special") && l.contains("Final"));
    }
}
