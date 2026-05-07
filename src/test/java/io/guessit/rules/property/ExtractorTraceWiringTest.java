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
        var out = sw.toString();
        assertThat(out).contains("Looking for year");
        assertThat(out).contains("Trying regex \\d{4}");
        assertThat(out).contains("Considered '2020'").contains("accepted");
    }

    @Test
    void screenSizeExtractorPropagatesTraceToPatternMatcher() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults()).guess("Movie.1080p.mkv", trace);
        var out = sw.toString();
        assertThat(out).contains("Looking for screen_size");
        assertThat(out).containsAnyOf("Trying needles", "Trying regex");
    }
}
