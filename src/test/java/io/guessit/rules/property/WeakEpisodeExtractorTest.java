package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeakEpisodeExtractorTest {
    @Test void twoDigitWeakWhenNotMovie() {
        var r = Guessit.parse("Show 12 720p HDTV.mkv").toMap();
        assertEquals(12, r.get("episode"));
    }
    @Test void weakDroppedWhenMovie() {
        var r = Guessit.parse("Movie.2010.12 Years.1080p.mkv",
            Options.builder().type("movie").build()).toMap();
        // year=2010 wins; "12" is weak and discarded under movie context.
        assertNull(r.get("episode"));
    }

    @Test void singleDigitOnlyForEpisodeType() {
        var r = Guessit.parse("Show.5.HDTV.mkv", Options.builder().type("episode").build()).toMap();
        assertEquals(5, r.get("episode"));
    }
    @Test void droppedAfterAudioCodec() {
        // Python RemoveWeak: a weak number directly after audio_codec/source/etc. is dropped.
        var r = Guessit.parse("Show AC3 12 .mkv").toMap();
        assertNull(r.get("episode"));
    }
}
