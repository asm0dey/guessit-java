package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import io.guessit.engine.DebugTrace;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorTraceWiringTest {

    @Test
    void yearExtractorPropagatesTraceToPatternMatcher() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults()).guess("Movie.2020.mkv", trace);

        assertThat(sw.toString()).contains(
                "Looking for year",
                "Considered '2020'",
                "accepted"
        ).containsAnyOf(
                "Trying regex [0-9]{4}",
                "Trying regex \\d{4}"
        );
    }

    @Test
    void screenSizeExtractorPropagatesTraceToPatternMatcher() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults()).guess("Movie.1080p.mkv", trace);

        assertThat(sw.toString())
                .contains("Looking for screen_size")
                .containsAnyOf("Trying needles", "Trying regex");
    }
}
