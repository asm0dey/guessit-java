package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals("1080p", ctx.matches.named("screen_size").findFirst().get().value());
    }
    @Test void interlaced1080i() {
        var ctx = run("Show.2015.1080i.HDTV.mkv");
        assertEquals("1080i", ctx.matches.named("screen_size").findFirst().get().value());
    }
    @Test void widthByHeight() {
        var ctx = run("Movie.2015.1920x1080.mkv");
        // standard ar, width+height present → normalize to "1080p"
        assertEquals("1080p", ctx.matches.named("screen_size").findFirst().get().value());
        assertEquals(1.778, ((Number) ctx.matches.named("aspect_ratio").findFirst().get().value()).doubleValue(), 0.001);
    }
    @Test void fourK() {
        var ctx = run("Movie.4K.mkv");
        assertEquals("2160p", ctx.matches.named("screen_size").findFirst().get().value());
    }
    @Test void frameRate24p() {
        var ctx = run("Movie.2015.1080p24.mkv");
        assertEquals("1080p", ctx.matches.named("screen_size").findFirst().get().value());
        assertNotNull(ctx.matches.named("frame_rate").findFirst().orElse(null));
    }
    @Test void rejectsLooseDigits() {
        var ctx = run("File.no.resolution.here.mkv");
        assertEquals(0L, ctx.matches.named("screen_size").count());
    }

    @Test void widthHeightWithSpaces() {
        var r1 = Guessit.parse("500 x 480", Options.defaults()).toMap();
        assertEquals("500x480", r1.get("screen_size"));
        var r2 = Guessit.parse("500 * 480", Options.defaults()).toMap();
        assertEquals("500x480", r2.get("screen_size"));
    }
}
