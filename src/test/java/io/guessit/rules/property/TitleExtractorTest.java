package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleExtractorTest {
    @Test void simpleFilepartHoleBecomesTitle() {
        var r = Guessit.parse("Movie.Name.2020.1080p.BluRay-RG.mkv").toMap();
        assertThat(r).containsEntry("title", "Movie Name");
    }
    @Test void dashSplitYieldsAlternativeTitle() {
        var r = Guessit.parse("Main Title - Alt Title.2020.mkv").toMap();
        assertThat(r)
                .containsEntry("title", "Main Title")
                .containsEntry("alternative_title", "Alt Title");
    }
    @Test void serieNameFilepartRoutesInnerToEpisodeTitle() {
        var r = Guessit.parse("Caprica/Season 1/Apotheosis.mkv").toMap();
        assertThat(r)
                .containsEntry("title", "Caprica")
                .containsEntry("episode_title", "Apotheosis")
                .containsEntry("season", 1);
    }
    @Test void preferTitleWithYearFilepart() {
        var r = Guessit.parse("Foo/Movie.Name.2020.1080p.mkv").toMap();
        assertThat(r).containsEntry("title", "Movie Name");
    }
    @Test void expectedTitleEmitsExpectedTaggedMatch() {
        var opts = Options.builder().expectedTitle(java.util.List.of("My Show")).build();
        var r = Guessit.parse("My.Show.2020.mkv", opts).toMap();
        assertThat(r).containsEntry("title", "My Show");
    }
}
