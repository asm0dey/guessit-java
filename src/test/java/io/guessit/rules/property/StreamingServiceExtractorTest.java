package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StreamingServiceExtractorTest {
    @Test void amzn() {
        var r = Guessit.parse("Show.S01.AMZN.WEB-DL.mkv");
        assertEquals("Amazon Prime", r.streamingService());
    }
    @Test void atvp() {
        var r = Guessit.parse("Show.S01.ATVP.WEB-DL.mkv");
        assertEquals("AppleTV", r.streamingService());
    }
    @Test void disneyPlus() {
        var r = Guessit.parse("Show.S01.DSNP.WEB-DL.mkv");
        assertEquals("Disney+", r.streamingService());
    }
    @Test void notMatchedWithoutSourceContext() {
        var r = Guessit.parse("File.CC.foo");
        assertNotEquals("Comedy Central", r.streamingService());
    }
}
