package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(run("Movie.2015.1080p.AAC.mkv").matches.named(MatchName.AUDIO_CODEC).findFirst().get().value()).isEqualTo("AAC");
    }
    @Test void mp3() {
        assertThat(run("Movie.2015.MP3.avi").matches.named(MatchName.AUDIO_CODEC).findFirst().get().value()).isEqualTo("MP3");
    }
    @Test void dolbyDigital_ac3() {
        assertThat(run("Movie.2015.AC3.mkv").matches.named(MatchName.AUDIO_CODEC).findFirst().get().value()).isEqualTo("Dolby Digital");
    }
    @Test void dts() {
        assertThat(run("Movie.2015.DTS.mkv").matches.named(MatchName.AUDIO_CODEC).findFirst().get().value()).isEqualTo("DTS");
    }
    @Test void dtsHd() {
        assertThat(run("Movie.2015.DTS-HD.mkv").matches.named(MatchName.AUDIO_CODEC).findFirst().get().value()).isEqualTo("DTS-HD");
    }
    @Test void channels_5_1() {
        assertThat(run("Movie.2015.5.1.mkv").matches.named(MatchName.AUDIO_CHANNELS).findFirst().get().value()).isEqualTo("5.1");
    }
    @Test void channels_2_0() {
        assertThat(run("Movie.2015.2.0.mkv").matches.named(MatchName.AUDIO_CHANNELS).findFirst().get().value()).isEqualTo("2.0");
    }
    @Test void rejectsLooseLetters() {
        assertThat(run("Movie.AACX.mkv").matches.named(MatchName.AUDIO_CODEC).findAny()).isEmpty();
    }
    @Test void channels_5_1_fullPipeline() {
        var r = parse("Hotel.Hell.S01E01.720p.DD5.1.448kbps-ALANiS");
        assertThat(r.audioChannels()).containsOnly("5.1");
    }
}
