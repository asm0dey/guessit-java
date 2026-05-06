package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeakEpisodeExtractorTest {
    @Test void twoDigitWeakWhenNotMovie() {
        var r = Guessit.parse("Show 12 720p HDTV.mkv");
        assertThat(r.episode()).isEqualTo(12);
    }
    @Test void weakDroppedWhenMovie() {
        var r = Guessit.parse("Movie.2010.12 Years.1080p.mkv",
            OptionsBuilder.options().type("movie").build());
        // year=2010 wins; "12" is weak and discarded under movie context.
        assertNull(r.episode());
    }

    @Test void singleDigitOnlyForEpisodeType() {
        var r = Guessit.parse("Show.5.HDTV.mkv", OptionsBuilder.options().type("episode").build());
        assertThat(r.episode()).isEqualTo(5);
    }
    @Test void droppedAfterAudioCodec() {
        // Python RemoveWeak: a weak number directly after audio_codec/source/etc. is dropped.
        var r = Guessit.parse("Show AC3 12 .mkv");
        assertNull(r.episode());
    }
}
