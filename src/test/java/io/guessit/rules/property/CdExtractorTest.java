package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CdExtractorTest {
    @Test void cdOnly() {
        var r = Guessit.parse("Movie.cd1.avi");
        assertThat(r.cd()).isEqualTo(1);
        assertThat(r.cdCount()).isNull();
    }
    @Test void cdOfCount() {
        var r = Guessit.parse("Movie.cd1of2.avi");
        assertThat(r.cd()).isEqualTo(1);
        assertThat(r.cdCount()).isEqualTo(2);
    }
    @Test void cdCountAlone() {
        var r = Guessit.parse("Movie.2cds.avi");
        assertThat(r.cd()).isNull();
        assertThat(r.cdCount()).isEqualTo(2);
    }
    @Test void rejectsOutOfRange() {
        var r = Guessit.parse("Movie.cd100.avi");
        assertThat(r.cd()).isNull();
    }
}
