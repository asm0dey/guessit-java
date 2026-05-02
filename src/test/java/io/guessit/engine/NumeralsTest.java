package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NumeralsTest {
    @Test void parsesDigital() {
        assertEquals(7, Numerals.parse("7"));
        assertEquals(123, Numerals.parse("123"));
    }
    @Test void parsesRoman() {
        assertEquals(4, Numerals.parse("IV"));
        assertEquals(9, Numerals.parse("IX"));
        assertEquals(1994, Numerals.parse("MCMXCIV"));
    }
    @Test void parsesEnglishWord() {
        assertEquals(0, Numerals.parse("zero"));
        assertEquals(7, Numerals.parse("seven"));
        assertEquals(20, Numerals.parse("twenty"));
    }
    @Test void parsesFrenchWord() {
        assertEquals(8, Numerals.parse("huit"));
        assertEquals(17, Numerals.parse("dix-sept"));
        assertEquals(17, Numerals.parse("dixsept"));
    }
    @Test void cleanWrappers() {
        // Python clean=True strips leading/trailing non-digits.
        assertEquals(42, Numerals.parse("ep42"));
        assertEquals(3, Numerals.parse("(3)"));
    }
    @Test void invalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> Numerals.parse("foo"));
    }
    @Test void numeralRegexSourceMatchesAllVariants() {
        var p = java.util.regex.Pattern.compile("^" + Numerals.NUMERAL + "$");
        assertTrue(p.matcher("12").matches());
        assertTrue(p.matcher("MCMXCIV").matches());
        assertTrue(p.matcher("seven").matches());
    }
}
