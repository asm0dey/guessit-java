package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.util.BitRate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BitRateExtractorTest {
    @Test void parsesKbpsAudio() {
        var r = Guessit.parse("Movie.320Kbps.mkv");
        assertThat(r.audioBitRate()).isEqualTo(BitRate.fromString("320Kbps"));
    }
    @Test void parsesMbpsFloat() {
        var r = Guessit.parse("Movie.1.5Mbps.mkv");
        assertThat(r.audioBitRate()).isEqualTo(BitRate.fromString("1.5Mbps"));
    }
    @Test void noEmissionWithoutSepsSurround() {
        var r = Guessit.parse("abc320Kbpsxyz.mkv");
        assertThat(r.audioBitRate()).isNull();
        assertThat(r.videoBitRate()).isNull();
        assertThat(r.bitRate()).isNull();
    }
}
