package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BonusExtractorTest {
    @Test void detectsBonusNumberAndTitle() {
        var r = Guessit.parse("Movie.x05.Behind.The.Scenes.mkv");
        assertThat(r.bonus()).isEqualTo(5);
        assertThat(r.bonusTitle()).isEqualTo("Behind The Scenes");
    }
    @Test void losesToVideoCodec() {
        // x264 must remain video_codec, not bonus=264
        var r = Guessit.parse("Movie.x264-RG.mkv");
        assertThat(r.bonus()).isNull();
        assertThat(r.videoCodec()).contains("H.264");
    }
}
