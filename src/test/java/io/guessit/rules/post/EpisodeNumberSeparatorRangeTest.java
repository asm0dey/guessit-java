package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EpisodeNumberSeparatorRangeTest {
    @Test void expandsHyphenRange() {
        var r = Guessit.parse("[Hatsuyuki]_Bleach_-_16-20_(191-195)_[1280x720].mkv");
        assertThat(r.episodeList()).contains(16, 17, 18, 19, 20);
    }
}
