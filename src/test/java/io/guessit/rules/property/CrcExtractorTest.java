package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CrcExtractorTest {
    @Test void hex8Crc32() {
        assertThat(Guessit.parse("Show.S01E02.[ABCD1234].mkv").crc32())
            .isEqualToIgnoringCase("ABCD1234");
    }
    @Test void detectsUnbracketed() {
        // Also matches when surrounded by separators
        var r = Guessit.parse("Show.S01E02.12345678.x264.mkv");
        assertThat(r.crc32()).isEqualToIgnoringCase("12345678");
    }
}
