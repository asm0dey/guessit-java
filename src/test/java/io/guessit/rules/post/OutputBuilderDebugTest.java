package io.guessit.rules.post;

import io.guessit.Guessit;
import io.guessit.Options;
import io.guessit.engine.DebugTrace;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class OutputBuilderDebugTest {

    @Test
    void emitsSetSubstepPerProperty() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.Name.2020.1080p.BluRay.x264-GRP.mkv", trace);
        var out = sw.toString();
        assertThat(out).contains("Set year ← 2020");
        assertThat(out).contains("Set screen_size ← 1080p");
        assertThat(out).contains("Set source ← Blu-ray");
        assertThat(out).contains("Set video_codec ← H.264");
        assertThat(out).contains("Set release_group ← GRP");
    }
}
