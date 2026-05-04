package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YearSeasonTest {
    @Test
    void yearWithEpisodeNoSeasonAddsSeason() {
        var r = Guessit.parse("Show.2014.E03.mkv");
        assertThat(r.season()).isEqualTo(2014);
    }
}
