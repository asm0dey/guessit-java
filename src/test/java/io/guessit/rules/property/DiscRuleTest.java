package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscRuleTest {
    @Test void disc() {
        var r = Guessit.parse("Show.S01.Disc.2.mkv");
        assertEquals(2, r.extras().get("disc"));
    }
    @Test void dvd() {
        var r = Guessit.parse("Show.S01.DVD3.mkv");
        assertEquals(3, r.extras().get("disc"));
    }
}
