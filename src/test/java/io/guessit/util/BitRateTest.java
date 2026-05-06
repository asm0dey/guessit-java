package io.guessit.util;

import org.junit.jupiter.api.Test;

import static io.guessit.util.BitRate.fromString;
import static org.assertj.core.api.Assertions.assertThat;

class BitRateTest {
    @Test void parsesKbps() {
        assertThat(fromString("320Kbps").format()).isEqualTo("320 Kbps");
    }
    @Test void parsesMbpsFloat() {
        assertThat(fromString("1.5Mbps").format()).isEqualTo("1.5 Mbps");
    }
    @Test void normalisesBitsToBps() {
        assertThat(fromString("128kbits").format()).isEqualTo("128 Kbps");
        assertThat(fromString("128kbit").format()).isEqualTo("128 Kbps");
    }
    @Test void capitaliseUnitFirstLetterOnly() {
        assertThat(fromString("320KBPS").format()).isEqualTo("320 Kbps");
        assertThat(fromString("320kbps").format()).isEqualTo("320 Kbps");
    }
}
