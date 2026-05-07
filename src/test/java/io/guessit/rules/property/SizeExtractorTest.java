package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.util.Size.fromString;
import static org.assertj.core.api.Assertions.assertThat;

class SizeExtractorTest {
    @Test void parsesIntegerGigabytes() {
        var r = parse("Movie.4.7gb.mkv");
        assertThat(r.size()).isEqualTo(fromString("4.7GB"));
    }
    @Test void parsesMegabytes() {
        var r = parse("Movie.700mb.mkv");
        assertThat(r.size()).isEqualTo(fromString("700MB"));
    }
    @Test void requiresSepsSurround() {
        var r = Guessit.parse("abc500mbxyz.mkv");
        assertThat(r.size()).isNull();
    }
}
