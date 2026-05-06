package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class VersionExtractorTest {
    @Test void versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv");
        assertThat(r.version()).isEqualTo(2);
    }
    @Test void detachedVersionNoEpisodeIsDropped() {
        var r = Guessit.parse("v3 randomshow.mkv");
        assertThat(r.version()).isEqualTo(3);
    }
    @Test void noVersion() {
        var r = Guessit.parse("Show.S01E02.mkv");
        assertNull(r.version());
    }
}
