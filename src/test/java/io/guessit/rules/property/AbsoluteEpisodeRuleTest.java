package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbsoluteEpisodeRuleTest {
    @SuppressWarnings("unchecked")
    @Test void standaloneAbsolute() {
        // No SxxExx present and no -E option, so WeakDuplicateExtractor parses 313 as season=3 episode=13
        var r = Guessit.parse("Show.313.314.HDTV.mkv").toMap();
        assertEquals(List.of(13, 14), r.get("episode"));
        assertNull(r.get("absolute_episode"));
    }
}
