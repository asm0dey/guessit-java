package io.guessit.rules.property;

import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static java.lang.System.err;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeasonEpisodeExtractorTest {
    @Test void s01e02() {
        var r = parse("Show.S01E02.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }
    @Test void multiEpisode_S01E02E03() {
        var r = parse("Show.S01E02E03.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episodeList()).isEqualTo(of(2, 3));
    }
    @Test void shortForm_01x02() {
        var r = parse("Show.01x02.HDTV.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }
    @Test void multiSeason_S01S02S03() {
        var r = parse("Show.S01S02S03.Pack.mkv");
        assertThat(r.seasonList()).isEqualTo(of(1, 2, 3));
    }
    @Test void rangeDash_S01E02_E04() {
        var result = parse("Show.S01E02-04.mkv");
        err.println("DEBUG result: " + result);
        assertThat(result.season()).isEqualTo(1);
        assertThat(result.episodeList()).isEqualTo(of(2, 3, 4));
    }
    @Test void capPattern_Cap_102() {
        var r = parse("Show.Cap.102.HDTV.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }
    @Test void seasonOnly_S01() {
        var r = parse("Show.S01.HDTV.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertNull(r.episode());
    }
}
