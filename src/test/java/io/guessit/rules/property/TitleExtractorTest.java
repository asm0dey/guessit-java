package io.guessit.rules.property;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.OptionsBuilder.options;
import static java.util.List.of;

class TitleExtractorTest {
    @Test void simpleFilepartHoleBecomesTitle() {
        var r = parse("Movie.Name.2020.1080p.BluRay-RG.mkv");
        Assertions.assertThat(r.title()).isEqualTo("Movie Name");
    }
    @Test void dashSplitYieldsAlternativeTitle() {
        var r = parse("Main Title - Alt Title.2020.mkv");
        Assertions.assertThat(r.title()).isEqualTo("Main Title");
        Assertions.assertThat(r.alternativeTitleList() != null && !r.alternativeTitleList().isEmpty()
                ? r.alternativeTitleList().getFirst()
                : null).isEqualTo("Alt Title");
    }
    @Test void serieNameFilepartRoutesInnerToEpisodeTitle() {
        var r = parse("Caprica/Season 1/Apotheosis.mkv");
        Assertions.assertThat(r.title()).isEqualTo("Caprica");
        Assertions.assertThat(r.episodeTitle()).isEqualTo("Apotheosis");
        Assertions.assertThat(r.season()).isEqualTo(1);
    }
    @Test void preferTitleWithYearFilepart() {
        var r = parse("Foo/Movie.Name.2020.1080p.mkv");
        Assertions.assertThat(r.title()).isEqualTo("Movie Name");
    }
    @Test void expectedTitleEmitsExpectedTaggedMatch() {
        var opts = options().expectedTitle(of("My Show")).build();
        var r = parse("My.Show.2020.mkv", opts);
        Assertions.assertThat(r.title()).isEqualTo("My Show");
    }
}
