package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SeasonEpisodeCompactLastTest {
    @Test void the100_109_keepsSeason1Episode9() {
        var r = Guessit.parse("the.100.109.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(9);
    }
    @Test void bracket401_funRun_keepsEpisode1() {
        var r = Guessit.parse("[401] Fun Run.mkv");
        assertThat(r.episode()).isEqualTo(1);
        assertThat(r.season()).isEqualTo(4);
    }
    @Test void e112263106_keepsEpisode6() {
        var r = Guessit.parse("11.22.63.106.mkv");
        assertThat(r.episode()).isEqualTo(6);
    }
    @Test void foobar_213_keepsEpisode13() {
        var r = Guessit.parse("foobar.213.mkv");
        assertThat(r.episode()).isEqualTo(13);
        assertThat(r.season()).isEqualTo(2);
    }
}
