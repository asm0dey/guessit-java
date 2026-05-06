package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.Options.defaults;
import static io.guessit.engine.MatchName.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SuppressWarnings("OptionalGetWithoutIsPresent")
class ScreenSizeExtractorTest {

    private static ParseContext run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        new PathMarker().produce(ctx);
        new GroupMarker().produce(ctx);
        var x = new ScreenSizeExtractor();
        x.extract(ctx);
        ConflictSolver.solve(ctx.matches);
        x.postProcess(ctx);
        return ctx;
    }

    @Test void progressive1080p() {
        var ctx = run("Movie.2015.1080p.BluRay.mkv");
        assertThat(ctx.matches.named(SCREEN_SIZE).findFirst().get().value()).isEqualTo("1080p");
    }
    @Test void interlaced1080i() {
        var ctx = run("Show.2015.1080i.HDTV.mkv");
        assertThat(ctx.matches.named(SCREEN_SIZE).findFirst().get().value()).isEqualTo("1080i");
    }
    @Test void widthByHeight() {
        var ctx = run("Movie.2015.1920x1080.mkv");
        // standard ar, width+height present → normalize to "1080p"
        assertThat(ctx.matches.named(SCREEN_SIZE).findFirst().get().value()).isEqualTo("1080p");
        assertEquals(1.778, ((Number) ctx.matches.named(ASPECT_RATIO).findFirst().get().value()).doubleValue(), 0.001);
    }
    @Test void fourK() {
        var ctx = run("Movie.4K.mkv");
        assertThat(ctx.matches.named(SCREEN_SIZE).findFirst().get().value()).isEqualTo("2160p");
    }
    @Test void frameRate24p() {
        var ctx = run("Movie.2015.1080p24.mkv");
        assertThat(ctx.matches.named(SCREEN_SIZE).findFirst().get().value()).isEqualTo("1080p");
        assertNotNull(ctx.matches.named(FRAME_RATE).findFirst().orElse(null));
    }
    @Test void rejectsLooseDigits() {
        var ctx = run("File.no.resolution.here.mkv");
        assertThat(ctx.matches.named(SCREEN_SIZE).count()).isEqualTo(0L);
    }

    @Test void widthHeightWithSpaces() {
        var r1 = parse("500 x 480", defaults());
        assertThat(r1.screenSize()).isEqualTo("500x480");
        var r2 = parse("500 * 480", defaults());
        assertThat(r2.screenSize()).isEqualTo("500x480");
    }
}
