package io.guessit.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuantityTest {
    @Test
    void formatsBitRate() {
        assertThat(BitRate.fromString("1.5Mbps").format()).isEqualTo("1.5 Mbps");
    }

    @Test
    void formatsIntegerSize() {
        assertThat(Size.fromString("4GB").format()).isEqualTo("4 GB");
    }

    @Test
    void formatsDecimalSize() {
        assertThat(Size.fromString("4.7GB").format()).isEqualTo("4.7 GB");
    }

    @Test
    void parsesFromString() {
        assertThat(Quantity.parse("1.5 Mbps")).isEqualTo(BitRate.fromString("1.5Mbps"));
        assertThat(Quantity.parse("800 Kbps")).isEqualTo(BitRate.fromString("800Kbps"));
    }
}
