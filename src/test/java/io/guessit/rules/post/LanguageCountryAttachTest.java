package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageCountryAttachTest {

    @Test
    void ptBrBecomesPortugueseWithBrazilCountry() {
        var r = Guessit.parse("Movie.pt-BR.1080p.mkv");
        assertThat(r.language()).hasSize(1);
        var l = r.language().getFirst();
        assertThat(l.alpha3()).isEqualTo("por");
        assertThat(l.country()).isNotNull();
        assertThat(l.country().alpha2()).isEqualTo("BR");
    }

    @Test
    void deChBecomesGermanWithSwissCountry() {
        var r = Guessit.parse("Movie.de-CH.1080p.mkv");
        assertThat(r.language()).hasSize(1);
        var l = r.language().getFirst();
        assertThat(l.alpha2()).isEqualTo("de");
        assertThat(l.country()).isNotNull();
        assertThat(l.country().alpha2()).isEqualTo("CH");
    }
}
