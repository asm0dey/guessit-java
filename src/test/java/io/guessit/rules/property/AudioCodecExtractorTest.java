package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("OptionalGetWithoutIsPresent")
class AudioCodecExtractorTest {
    private static ParseContext run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        var x = new AudioCodecExtractor();
        x.extract(ctx);
        ConflictSolver.solve(ctx.matches);
        x.postProcess(ctx);
        return ctx;
    }

    @Test void aac() {
        assertEquals("AAC", run("Movie.2015.1080p.AAC.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void mp3() {
        assertEquals("MP3", run("Movie.2015.MP3.avi").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void dolbyDigital_ac3() {
        assertEquals("Dolby Digital", run("Movie.2015.AC3.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void dts() {
        assertEquals("DTS", run("Movie.2015.DTS.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void dtsHd() {
        assertEquals("DTS-HD", run("Movie.2015.DTS-HD.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void channels_5_1() {
        assertEquals("5.1", run("Movie.2015.5.1.mkv").matches.named("audio_channels").findFirst().get().value());
    }
    @Test void channels_2_0() {
        assertEquals("2.0", run("Movie.2015.2.0.mkv").matches.named("audio_channels").findFirst().get().value());
    }
    @Test void rejectsLooseLetters() {
        assertTrue(run("Movie.AACX.mkv").matches.named("audio_codec").findAny().isEmpty());
    }
    @Test void channels_5_1_fullPipeline() {
        var r = parse("Hotel.Hell.S01E01.720p.DD5.1.448kbps-ALANiS").toMap();
        assertEquals("5.1", r.get("audio_channels"));
    }
}
