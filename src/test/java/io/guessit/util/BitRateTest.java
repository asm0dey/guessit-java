package io.guessit.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BitRateTest {
    @Test void parsesKbps() {
        assertEquals("320 Kbps", BitRate.fromString("320Kbps").format());
    }
    @Test void parsesMbpsFloat() {
        assertEquals("1.5 Mbps", BitRate.fromString("1.5Mbps").format());
    }
    @Test void normalisesBitsToBps() {
        assertEquals("128 Kbps", BitRate.fromString("128kbits").format());
        assertEquals("128 Kbps", BitRate.fromString("128kbit").format());
    }
    @Test void capitaliseUnitFirstLetterOnly() {
        assertEquals("320 Kbps", BitRate.fromString("320KBPS").format());
        assertEquals("320 Kbps", BitRate.fromString("320kbps").format());
    }
}
