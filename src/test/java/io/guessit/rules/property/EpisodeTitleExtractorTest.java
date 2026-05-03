package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodeTitleExtractorTest {
    @Test
    void episodeTitleFromPositionFillsHoleAfterEpisode() {
        var r = Guessit.parse("Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv").toMap();
        assertThat(r).containsEntry("title", "Show Name").containsEntry("episode_title", "Episode Title");
    }

    @Test
    void titleToEpisodeTitleRenamesSecondTitleAfterEpisode() {
        var r = Guessit.parse("Foo/Show.Name.S01E02.Episode.Title.mkv").toMap();
        assertThat(r).containsEntry("title", "Show Name").containsEntry("episode_title", "Episode Title");
    }

    @Test
    void filepart3() {
        var r = Guessit.parse("Series Name/Season 1/E01-episode-title.mkv").toMap();
        assertThat(r).containsEntry("title", "Series Name").containsEntry("episode", 1);
    }

    @Test
    void filepart2() {
        var r = Guessit.parse("Series Name S01/E01-episode-title.mkv").toMap();
        assertThat(r).containsEntry("title", "Series Name");
    }

    @Test
    void renameEpisodeTitleWhenMovieType() {
        var r = Guessit.parse("Movie - Alt Title.2020.mkv").toMap();
        assertThat(r).containsEntry("title", "Movie")
                .containsEntry("alternative_title", "Alt Title")
                .doesNotContainKey("episode_title");
    }
}
