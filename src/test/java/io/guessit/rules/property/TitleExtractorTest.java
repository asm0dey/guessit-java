package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleExtractorTest {
    @Test void simpleFilepartHoleBecomesTitle() {
        var r = Guessit.parse("Movie.Name.2020.1080p.BluRay-RG.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Movie Name");
    }
    @Test void dashSplitYieldsAlternativeTitle() {
        var r = Guessit.parse("Main Title - Alt Title.2020.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Main Title");
        assertThat(r.get("alternative_title")).isEqualTo("Alt Title");
    }
    @Test void serieNameFilepartRoutesInnerToEpisodeTitle() {
        var r = Guessit.parse("Caprica/Season 1/Apotheosis.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Caprica");
        assertThat(r.get("episode_title")).isEqualTo("Apotheosis");
        assertThat(r.get("season")).isEqualTo(1);
    }
    @Test void preferTitleWithYearFilepart() {
        var r = Guessit.parse("Foo/Movie.Name.2020.1080p.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Movie Name");
    }
    @Test void expectedTitleEmitsExpectedTaggedMatch() {
        var opts = Options.builder().expectedTitle(java.util.List.of("My Show")).build();
        var r = Guessit.parse("My.Show.2020.mkv", opts).toMap();
        assertThat(r.get("title")).isEqualTo("My Show");
    }
}
