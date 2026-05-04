package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonYearTest {
    @Test
    void seasonValueLooksLikeYearAddsYear() {
        var r = Guessit.parse("Show.S2014.E03.mkv");
        assertThat(r.year()).isEqualTo(2014);
    }
}
