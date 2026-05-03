package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoCodecExtractorTest {

    private static ParseContext run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), OptionsConfig.empty());
        var x = new VideoCodecExtractor();
        x.extract(ctx);
        ConflictSolver.solve(ctx.matches);
        x.postProcess(ctx);
        return ctx;
    }

    @Test void h264() {
        assertEquals("H.264", run("Movie.2015.1080p.x264.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void h265() {
        assertEquals("H.265", run("Movie.2015.1080p.x265.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void hevc() {
        assertEquals("H.265", run("Movie.2015.1080p.HEVC.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void hevc10ColorDepth() {
        var ctx = run("Movie.2015.1080p.HEVC10.mkv");
        assertEquals("H.265", ctx.matches.named("video_codec").findFirst().get().value());
        assertEquals("10-bit", ctx.matches.named("color_depth").findFirst().get().value());
    }
    @Test void xvid() {
        assertEquals("Xvid", run("Movie.2015.XviD.avi").matches.named("video_codec").findFirst().get().value());
    }
    @Test void divx() {
        assertEquals("DivX", run("Movie.2015.DivX.avi").matches.named("video_codec").findFirst().get().value());
    }
    @Test void mpeg2() {
        assertEquals("MPEG-2", run("Movie.2015.MPEG-2.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void dxvaApi() {
        assertEquals("DXVA", run("Movie.2015.DXVA.mkv").matches.named("video_api").findFirst().get().value());
    }
    @Test void rejectsBareDigits() {
        assertTrue(run("Random.text.264.no.codec").matches.named("video_codec").findAny().isEmpty());
    }
}
