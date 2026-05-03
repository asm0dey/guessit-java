package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodeTitleExtractorTest {
    @Test void episodeTitleFromPositionFillsHoleAfterEpisode() {
        var r = Guessit.parse("Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat(r.get("episode_title")).isEqualTo("Episode Title");
    }
    @Test void titleToEpisodeTitleRenamesSecondTitleAfterEpisode() {
        var r = Guessit.parse("Foo/Show.Name.S01E02.Episode.Title.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat(r.get("episode_title")).isEqualTo("Episode Title");
    }
    @Test void filepart3() {
        var r = Guessit.parse("Series Name/Season 1/E01-episode-title.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Series Name");
        assertThat(r.get("episode")).isEqualTo(1);
    }
    @Test void filepart2() {
        var r = Guessit.parse("Series Name S01/E01-episode-title.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Series Name");
    }
    @Test void renameEpisodeTitleWhenMovieType() {
        var r = Guessit.parse("Movie.Name.2020.Bonus.Material.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Movie Name");
        assertThat(r.get("alternative_title")).isEqualTo("Bonus Material");
        assertThat(r.get("episode_title")).isNull();
    }
}
