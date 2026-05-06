package io.guessit.rules.property;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;

class SeasonEpisodeCompactLastTest {
    @Test void the100_109_keepsSeason1Episode9() {
        var r = parse("the.100.109.mkv");
        Assertions.assertThat(r.season()).isEqualTo(1);
        Assertions.assertThat(r.episode()).isEqualTo(9);
    }
    @Test void bracket401_funRun_keepsEpisode1() {
        var r = parse("[401] Fun Run.mkv");
        Assertions.assertThat(r.episode()).isEqualTo(1);
        Assertions.assertThat(r.season()).isEqualTo(4);
    }
    @Test void e112263106_keepsEpisode6() {
        var r = parse("11.22.63.106.mkv");
        Assertions.assertThat(r.episode()).isEqualTo(6);
    }
    @Test void foobar_213_keepsEpisode13() {
        var r = parse("foobar.213.mkv");
        Assertions.assertThat(r.episode()).isEqualTo(13);
        Assertions.assertThat(r.season()).isEqualTo(2);
    }
}
