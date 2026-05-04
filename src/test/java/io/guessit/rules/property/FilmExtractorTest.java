package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FilmExtractorTest {
    @Test void detectsFilmNumberAndPrefixTitle() {
        var r = Guessit.parse("My.Awesome.Series.f01.mkv");
        assertThat(r.film()).isEqualTo(1);
        assertThat(r.filmTitle()).isEqualTo("My Awesome Series");
    }
    @Test void rejectsThreeDigits() {
        var r = Guessit.parse("Movie.f100.mkv");
        assertThat(r.film()).isNull();
    }
}
