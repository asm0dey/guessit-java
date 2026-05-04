package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.util.Size;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SizeExtractorTest {
    @Test void parsesIntegerGigabytes() {
        var r = Guessit.parse("Movie.4.7gb.mkv");
        assertThat(r.size()).isEqualTo(Size.fromString("4.7GB"));
    }
    @Test void parsesMegabytes() {
        var r = Guessit.parse("Movie.700mb.mkv");
        assertThat(r.size()).isEqualTo(Size.fromString("700MB"));
    }
    @Test void requiresSepsSurround() {
        var r = Guessit.parse("abc500mbxyz.mkv");
        assertThat(r.size()).isNull();
    }
}
