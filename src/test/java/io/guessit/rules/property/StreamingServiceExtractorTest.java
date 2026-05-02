package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingServiceExtractorTest {
    @Test void amzn() {
        var r = Guessit.parse("Show.S01.AMZN.WEB-DL.mkv").toMap();
        assertEquals("Amazon Prime", r.get("streaming_service"));
    }
    @Test void atvp() {
        var r = Guessit.parse("Show.S01.ATVP.WEB-DL.mkv").toMap();
        assertEquals("AppleTV", r.get("streaming_service"));
    }
    @Test void disneyPlus() {
        var r = Guessit.parse("Show.S01.DSNP.WEB-DL.mkv").toMap();
        assertEquals("Disney+", r.get("streaming_service"));
    }
    @Test void notMatchedWithoutSourceContext() {
        var r = Guessit.parse("File.CC.foo").toMap();
        assertNotEquals("Comedy Central", r.get("streaming_service"));
    }
}
