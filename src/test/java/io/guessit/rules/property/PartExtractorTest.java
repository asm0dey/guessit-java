package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PartExtractorTest {
    @Test void arabicPart() {
        assertThat(Guessit.parse("Movie.Part2.mkv").part()).isEqualTo(2);
    }
    @Test void ptShortPrefix() {
        assertThat(Guessit.parse("Movie.pt3.mkv").part()).isEqualTo(3);
    }
    @Test void romanPart() {
        assertThat(Guessit.parse("Movie.PartIII.mkv").part()).isEqualTo(3);
    }
    @Test void rejectsOutOfRange() {
        assertThat(Guessit.parse("Movie.Part200.mkv").part()).isNull();
    }
}
