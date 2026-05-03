package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscRuleTest {
    @Test void disc() {
        var r = Guessit.parse("Show.S01.Disc.2.mkv").toMap();
        assertEquals(2, r.get("disc"));
    }
    @Test void dvd() {
        var r = Guessit.parse("Show.S01.DVD3.mkv").toMap();
        assertEquals(3, r.get("disc"));
    }
}
