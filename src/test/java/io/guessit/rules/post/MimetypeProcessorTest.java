package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MimetypeProcessorTest {
    @Test void mp4() {
        assertThat(Guessit.parse("Movie.mp4").mimetype()).isEqualTo("video/mp4");
    }
    @Test void mkv() {
        assertThat(Guessit.parse("Movie.mkv").mimetype()).isEqualTo("video/x-matroska");
    }
    @Test void srt() {
        assertThat(Guessit.parse("Movie.srt").mimetype()).isEqualTo("application/x-subrip");
    }
}
