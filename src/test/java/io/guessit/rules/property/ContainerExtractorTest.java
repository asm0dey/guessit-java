package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerExtractorTest {

    private static List<Object> run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        new ContainerExtractor().extract(ctx);
        return ctx.matches.named("container").map(m -> m.value()).toList();
    }

    @Test void videoExtension() { assertEquals(List.of("mkv"), run("Movie.2015.mkv")); }
    @Test void subtitleExtension() { assertEquals(List.of("srt"), run("Movie.2015.srt")); }
    @Test void torrentExtension() { assertEquals(List.of("torrent"), run("Movie.2015.torrent")); }
    @Test void infoExtension() { assertEquals(List.of("nfo"), run("Movie.2015.nfo")); }
    @Test void noExtension_returnsBodyContainer() {
        // 'avi' appears in the body, no trailing extension.
        var values = run("Movie.avi.Title");
        assertTrue(values.contains("avi"));
    }
    @Test void unknownExtensionDropped() { assertTrue(run("Movie.2015.exe").isEmpty()); }
}
