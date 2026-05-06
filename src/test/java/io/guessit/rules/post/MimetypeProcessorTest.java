package io.guessit.rules.post;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;

class MimetypeProcessorTest {
    @Test void mp4() {
        Assertions.assertThat(parse("Movie.mp4").mimetype()).isEqualTo("video/mp4");
    }
    @Test void mkv() {
        Assertions.assertThat(parse("Movie.mkv").mimetype()).isEqualTo("video/x-matroska");
    }
    @Test void srt() {
        Assertions.assertThat(parse("Movie.srt").mimetype()).isEqualTo("application/x-subrip");
    }
}
