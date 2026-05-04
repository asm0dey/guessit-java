package io.guessit.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuantityTest {
    @Test
    void formatsBitRate() {
        assertEquals("1.5 Mbps", BitRate.fromString("1.5Mbps").format());
    }

    @Test
    void formatsIntegerSize() {
        assertEquals("4 GB", Size.fromString("4GB").format());
    }

    @Test
    void formatsDecimalSize() {
        assertEquals("4.7 GB", Size.fromString("4.7GB").format());
    }

    @Test
    void parsesFromString() {
        assertEquals(BitRate.fromString("1.5Mbps"), Quantity.parse("1.5 Mbps"));
        assertEquals(BitRate.fromString("800Kbps"), Quantity.parse("800 Kbps"));
    }
}
