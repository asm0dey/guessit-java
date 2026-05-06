package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerExtractorTest {

    private static List<Object> run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        new ContainerExtractor().extract(ctx);
        return ctx.matches.named(MatchName.CONTAINER).map(Match::value).toList();
    }

    @Test void videoExtension() {
        assertThat(run("Movie.2015.mkv")).isEqualTo(of("mkv"));
    }
    @Test void subtitleExtension() {
        assertThat(run("Movie.2015.srt")).isEqualTo(of("srt"));
    }
    @Test void torrentExtension() {
        assertThat(run("Movie.2015.torrent")).isEqualTo(of("torrent"));
    }
    @Test void infoExtension() {
        assertThat(run("Movie.2015.nfo")).isEqualTo(of("nfo"));
    }
    @Test void noExtension_returnsBodyContainer() {
        // 'avi' appears in the body, no trailing extension.
        var values = run("Movie.avi.Title");
        assertTrue(values.contains("avi"));
    }
    @Test void unknownExtensionDropped() {Assertions.assertThat(run("Movie.2015.exe")).isEmpty();}
}
