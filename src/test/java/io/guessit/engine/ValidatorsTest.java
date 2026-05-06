package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static io.guessit.engine.MatchName.G;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorsTest {
    private static Match m(int s, int e, String input) {
        return new Match(G, null, s, e, input.substring(s, e), 1000, java.util.Set.of(), false);
    }

    @Test void sepsSurround_atStartOfString() {
        Predicate<Match> v = Validators.sepsSurround("hello world");
        assertTrue(v.test(m(0, 5, "hello world")));        // start of string is a virtual sep
    }
    @Test void sepsSurround_atEndOfString() {
        Predicate<Match> v = Validators.sepsSurround("hello world");
        assertTrue(v.test(m(6, 11, "hello world")));       // end of string is a virtual sep
    }
    @Test void sepsSurround_lettersBefore_fails() {
        Predicate<Match> v = Validators.sepsSurround("ahello world");
        assertFalse(v.test(m(1, 6, "ahello world")));      // 'a' before
    }
    @Test void sepsSurround_lettersAfter_fails() {
        Predicate<Match> v = Validators.sepsSurround("hellow world");
        assertFalse(v.test(m(0, 5, "hellow world")));      // 'w' after
    }
    @Test void sepsBefore_only() {
        Predicate<Match> v = Validators.sepsBefore("xhellow");
        assertFalse(v.test(m(1, 6, "xhellow")));           // 'x' before — not a sep
        v = Validators.sepsBefore(".hello");
        assertTrue(v.test(m(1, 6, ".hello")));
    }
    @Test void sepsAfter_only() {
        Predicate<Match> v = Validators.sepsAfter("hello.");
        assertTrue(v.test(m(0, 5, "hello.")));
    }
}
