package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class EpisodeTitleExtractorTest {
    @Test
    void episodeTitleFromPositionFillsHoleAfterEpisode() {
        var r = Guessit.parse("Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv");
        assertEquals("Show Name", r.title());
        assertEquals("Episode Title", r.episodeTitle());
    }

    @Test
    void titleToEpisodeTitleRenamesSecondTitleAfterEpisode() {
        var r = Guessit.parse("Foo/Show.Name.S01E02.Episode.Title.mkv");
        assertEquals("Show Name", r.title());
        assertEquals("Episode Title", r.episodeTitle());
    }

    @Test
    void filepart3() {
        var r = Guessit.parse("Series Name/Season 1/E01-episode-title.mkv");
        assertEquals("Series Name", r.title());
        assertEquals(1, r.episode());
    }

    @Test
    void filepart2() {
        var r = Guessit.parse("Series Name S01/E01-episode-title.mkv");
        assertEquals("Series Name", r.title());
    }

    @Test
    void renameEpisodeTitleWhenMovieType() {
        var r = Guessit.parse("Movie - Alt Title.2020.mkv");
        assertEquals("Movie", r.title());
        var altTitleList = r.alternativeTitleList();
        assertTrue(altTitleList != null && altTitleList.contains("Alt Title"));
        assertNull(r.episodeTitle());
    }
}
