package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StreamingServiceExtractorTest {
    @Test void amzn() {
        var r = parse("Show.S01.AMZN.WEB-DL.mkv");
        assertThat(r.streamingService()).isEqualTo("Amazon Prime");
    }
    @Test void atvp() {
        var r = parse("Show.S01.ATVP.WEB-DL.mkv");
        assertThat(r.streamingService()).isEqualTo("AppleTV");
    }
    @Test void disneyPlus() {
        var r = parse("Show.S01.DSNP.WEB-DL.mkv");
        assertThat(r.streamingService()).isEqualTo("Disney+");
    }
    @Test void notMatchedWithoutSourceContext() {
        var r = Guessit.parse("File.CC.foo");
        assertNotEquals("Comedy Central", r.streamingService());
    }
}
