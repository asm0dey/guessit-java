package io.guessit.rules.property;

import io.guessit.Guessit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

class FilmExtractorTest {
    @Test void detectsFilmNumberAndPrefixTitle() {
        var r = parse("My.Awesome.Series.f01.mkv");
        Assertions.assertThat(r.film()).isEqualTo(1);
        Assertions.assertThat(r.filmTitle()).isEqualTo("My Awesome Series");
    }
    @Test void rejectsThreeDigits() {
        var r = Guessit.parse("Movie.f100.mkv");
        assertThat(r.film()).isNull();
    }
}
