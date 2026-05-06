package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeTitleExtractorTest {
    @Test
    void episodeTitleFromPositionFillsHoleAfterEpisode() {
        var r = Guessit.parse("Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv");
        assertThat(r.title()).isEqualTo("Show Name");
        assertThat(r.episodeTitle()).isEqualTo("Episode Title");
    }

    @Test
    void titleToEpisodeTitleRenamesSecondTitleAfterEpisode() {
        var r = Guessit.parse("Foo/Show.Name.S01E02.Episode.Title.mkv");
        assertThat(r.title()).isEqualTo("Show Name");
        assertThat(r.episodeTitle()).isEqualTo("Episode Title");
    }

    @Test
    void filepart3() {
        var r = Guessit.parse("Series Name/Season 1/E01-episode-title.mkv");
        assertThat(r.title()).isEqualTo("Series Name");
        assertThat(r.episode()).isEqualTo(1);
    }

    @Test
    void filepart2() {
        var r = Guessit.parse("Series Name S01/E01-episode-title.mkv");
        assertThat(r.title()).isEqualTo("Series Name");
    }

    @Test
    void renameEpisodeTitleWhenMovieType() {
        var r = Guessit.parse("Movie - Alt Title.2020.mkv");
        assertThat(r.title()).isEqualTo("Movie");
        var altTitleList = r.alternativeTitleList();
        assertTrue(altTitleList != null && altTitleList.contains("Alt Title"));
        assertNull(r.episodeTitle());
    }
}
