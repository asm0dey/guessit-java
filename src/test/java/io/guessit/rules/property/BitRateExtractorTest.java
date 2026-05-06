package io.guessit.rules.property;

import io.guessit.Guessit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.util.BitRate.fromString;
import static org.assertj.core.api.Assertions.assertThat;

class BitRateExtractorTest {
    @Test void parsesKbpsAudio() {
        var r = parse("Movie.320Kbps.mkv");
        Assertions.assertThat(r.audioBitRate()).isEqualTo(fromString("320Kbps"));
    }
    @Test void parsesMbpsFloat() {
        var r = parse("Movie.1.5Mbps.mkv");
        Assertions.assertThat(r.audioBitRate()).isEqualTo(fromString("1.5Mbps"));
    }
    @Test void noEmissionWithoutSepsSurround() {
        var r = Guessit.parse("abc320Kbpsxyz.mkv");
        assertThat(r.audioBitRate()).isNull();
        assertThat(r.videoBitRate()).isNull();
        assertThat(r.bitRate()).isNull();
    }
}
