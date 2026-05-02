package io.guessit.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SepsTest {
    @Test void containsAllPythonSeparators() {
        // Python guessit: seps = " [](){}+*|=-_~#/\\.,;:"
        for (char c : " [](){}+*|=-_~#/\\.,;:".toCharArray()) {
            assertTrue(Seps.isSep(c), "Missing separator: " + c);
        }
    }
    @Test void rejectsLettersAndDigits() {
        assertFalse(Seps.isSep('a'));
        assertFalse(Seps.isSep('5'));
        assertFalse(Seps.isSep('Z'));
    }
    @Test void escapedForRegexCharClass() {
        // Should be usable inside [...] without breaking the class.
        var re = java.util.regex.Pattern.compile("[" + Seps.regexCharClass() + "]");
        assertTrue(re.matcher(".").find());
        assertTrue(re.matcher(" ").find());
        assertTrue(re.matcher("\\").find());
    }
}
