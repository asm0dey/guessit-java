package io.guessit.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuantityTest {
    @Test
    void formatsBitRate() {
        assertEquals("1.5 Mbps", new Quantity(1.5, "Mbps").format());
    }

    @Test
    void formatsIntegerSize() {
        assertEquals("4 GB", new Quantity(4.0, "GB").format());
    }

    @Test
    void formatsDecimalSize() {
        assertEquals("4.7 GB", new Quantity(4.7, "GB").format());
    }

    @Test
    void parsesFromString() {
        assertEquals(new Quantity(1.5, "Mbps"), Quantity.parse("1.5 Mbps"));
        assertEquals(new Quantity(800, "Kbps"), Quantity.parse("800 Kbps"));
    }
}
