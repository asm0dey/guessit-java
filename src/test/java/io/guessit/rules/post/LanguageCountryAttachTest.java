package io.guessit.rules.post;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

class LanguageCountryAttachTest {

    @Test
    void ptBrBecomesPortugueseWithBrazilCountry() {
        var r = parse("Movie.pt-BR.1080p.mkv");
        assertThat(r.language()).hasSize(1);
        var l = r.language().getFirst();
        Assertions.assertThat(l.alpha3()).isEqualTo("por");
        assertThat(l.country()).isNotNull();
        Assertions.assertThat(l.country().alpha2()).isEqualTo("BR");
    }

    @Test
    void deChBecomesGermanWithSwissCountry() {
        var r = parse("Movie.de-CH.1080p.mkv");
        assertThat(r.language()).hasSize(1);
        var l = r.language().getFirst();
        Assertions.assertThat(l.alpha2()).isEqualTo("de");
        assertThat(l.country()).isNotNull();
        Assertions.assertThat(l.country().alpha2()).isEqualTo("CH");
    }
}
