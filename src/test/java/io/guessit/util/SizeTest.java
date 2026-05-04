package io.guessit.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SizeTest {
    @Test void parsesIntMagnitude() {
        var s = Size.fromString("300MB");
        assertEquals(300.0, s.value(), 0.0);
        assertEquals("MB", s.unit());
        assertEquals("300 MB", s.format());
    }
    @Test void parsesFloatMagnitude() {
        var s = Size.fromString("1.5GB");
        assertEquals("1.5 GB", s.format());
    }
    @Test void normalisesUnitToUpperCaseStripsSeps() {
        assertEquals("4.7 GB", Size.fromString("4.7-gb").format());
        assertEquals("700 MB", Size.fromString("700.mb").format());
    }
}
