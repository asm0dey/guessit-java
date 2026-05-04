package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EditionExtractorTest {
    @Test void detectsCollectorsEdition() {
        assertThat(Guessit.parse("Movie.Collectors.Edition.mkv").edition())
            .containsExactly("Collector");
    }
    @Test void detectsDirectorsCutShortDC() {
        assertThat(Guessit.parse("Movie.DC.1080p.BluRay-RG.mkv").edition())
            .containsExactly("Director's Cut");
    }
    @Test void detectsExtended() {
        assertThat(Guessit.parse("Movie.Extended.1080p.mkv").edition())
            .containsExactly("Extended");
    }
    @Test void detectsRemastered() {
        assertThat(Guessit.parse("Movie.Remastered.mkv").edition())
            .containsExactly("Remastered");
    }
}
