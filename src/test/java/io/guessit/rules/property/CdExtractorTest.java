package io.guessit.rules.property;

import io.guessit.Guessit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

class CdExtractorTest {
    @Test void cdOnly() {
        var r = parse("Movie.cd1.avi");
        Assertions.assertThat(r.cd()).isEqualTo(1);
        assertThat(r.cdCount()).isNull();
    }
    @Test void cdOfCount() {
        var r = parse("Movie.cd1of2.avi");
        Assertions.assertThat(r.cd()).isEqualTo(1);
        Assertions.assertThat(r.cdCount()).isEqualTo(2);
    }
    @Test void cdCountAlone() {
        var r = parse("Movie.2cds.avi");
        assertThat(r.cd()).isNull();
        Assertions.assertThat(r.cdCount()).isEqualTo(2);
    }
    @Test void rejectsOutOfRange() {
        var r = Guessit.parse("Movie.cd100.avi");
        assertThat(r.cd()).isNull();
    }
}
