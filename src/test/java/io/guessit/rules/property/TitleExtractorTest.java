package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleExtractorTest {
    @Test void simpleFilepartHoleBecomesTitle() {
        var r = Guessit.parse("Movie.Name.2020.1080p.BluRay-RG.mkv");
        assertEquals("Movie Name", r.title());
    }
    @Test void dashSplitYieldsAlternativeTitle() {
        var r = Guessit.parse("Main Title - Alt Title.2020.mkv");
        assertEquals("Main Title", r.title());
        assertEquals("Alt Title", r.alternativeTitleList() != null && !r.alternativeTitleList().isEmpty()
            ? r.alternativeTitleList().get(0)
            : null);
    }
    @Test void serieNameFilepartRoutesInnerToEpisodeTitle() {
        var r = Guessit.parse("Caprica/Season 1/Apotheosis.mkv");
        assertEquals("Caprica", r.title());
        assertEquals("Apotheosis", r.episodeTitle());
        assertEquals(1, r.season());
    }
    @Test void preferTitleWithYearFilepart() {
        var r = Guessit.parse("Foo/Movie.Name.2020.1080p.mkv");
        assertEquals("Movie Name", r.title());
    }
    @Test void expectedTitleEmitsExpectedTaggedMatch() {
        var opts = OptionsBuilder.options().expectedTitle(java.util.List.of("My Show")).build();
        var r = Guessit.parse("My.Show.2020.mkv", opts);
        assertEquals("My Show", r.title());
    }
}
