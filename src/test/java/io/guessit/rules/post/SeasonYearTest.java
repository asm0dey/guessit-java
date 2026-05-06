package io.guessit.rules.post;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;

class SeasonYearTest {
    @Test
    void seasonValueLooksLikeYearAddsYear() {
        var r = parse("Show.S2014.E03.mkv");
        Assertions.assertThat(r.year()).isEqualTo(2014);
    }
}
