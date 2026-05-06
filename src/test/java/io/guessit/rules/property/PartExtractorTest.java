package io.guessit.rules.property;

import io.guessit.Guessit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

class PartExtractorTest {
    @Test void arabicPart() {
        Assertions.assertThat(parse("Movie.Part2.mkv").part()).isEqualTo(2);
    }
    @Test void ptShortPrefix() {
        Assertions.assertThat(parse("Movie.pt3.mkv").part()).isEqualTo(3);
    }
    @Test void romanPart() {
        Assertions.assertThat(parse("Movie.PartIII.mkv").part()).isEqualTo(3);
    }
    @Test void rejectsOutOfRange() {
        assertThat(Guessit.parse("Movie.Part200.mkv").part()).isNull();
    }
}
