package io.guessit.engine;

import io.guessit.engine.numerals.Numerals;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NumeralsTest {
    @Test void parsesDigital() {
        assertThat(Numerals.parse("7")).isEqualTo(7);
        assertThat(Numerals.parse("123")).isEqualTo(123);
    }
    @Test void parsesRoman() {
        assertThat(Numerals.parse("IV")).isEqualTo(4);
        assertThat(Numerals.parse("IX")).isEqualTo(9);
        assertThat(Numerals.parse("MCMXCIV")).isEqualTo(1994);
    }
    @Test void parsesEnglishWord() {
        assertThat(Numerals.parse("zero")).isZero();
        assertThat(Numerals.parse("seven")).isEqualTo(7);
        assertThat(Numerals.parse("twenty")).isEqualTo(20);
    }
    @Test void parsesFrenchWord() {
        assertThat(Numerals.parse("huit")).isEqualTo(8);
        assertThat(Numerals.parse("dix-sept")).isEqualTo(17);
        assertThat(Numerals.parse("dixsept")).isEqualTo(17);
    }
    @Test void cleanWrappers() {
        // Python clean=True strips leading/trailing non-digits.
        assertThat(Numerals.parse("ep42")).isEqualTo(42);
        assertThat(Numerals.parse("(3)")).isEqualTo(3);
    }
    @Test void invalidThrows() {
        assertThatThrownBy(() -> Numerals.parse("foo")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void numeralRegexSourceMatchesAllVariants() {
        var p = Numerals.NUMERAL_PATTERN;
        assertThat(p.matchesEntire("12")).isTrue();
        assertThat(p.matchesEntire("MCMXCIV")).isTrue();
        assertThat(p.matchesEntire("seven")).isTrue();
    }
}
