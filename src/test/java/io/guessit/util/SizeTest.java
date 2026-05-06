package io.guessit.util;

import org.junit.jupiter.api.Test;

import static io.guessit.util.Size.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SizeTest {
    @Test void parsesIntMagnitude() {
        var s = fromString("300MB");
        assertEquals(300.0, s.value(), 0.0);
        assertThat(s.unit()).isEqualTo("MB");
        assertThat(s.format()).isEqualTo("300 MB");
    }
    @Test void parsesFloatMagnitude() {
        var s = fromString("1.5GB");
        assertThat(s.format()).isEqualTo("1.5 GB");
    }
    @Test void normalisesUnitToUpperCaseStripsSeps() {
        assertThat(fromString("4.7-gb").format()).isEqualTo("4.7 GB");
        assertThat(fromString("700.mb").format()).isEqualTo("700 MB");
    }
}
