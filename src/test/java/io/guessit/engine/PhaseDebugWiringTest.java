package io.guessit.engine;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseDebugWiringTest {

    @Test
    void debugTraceShowsAllPhaseHeadersWithDescriptions() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.Name.2020.1080p.BluRay.x264-GRP.mkv", trace);
        var out = sw.toString();
        assertThat(out)
            .contains("Markers phase — ")
            .contains("Extractors phase — ")
            .contains("Conflicts phase — ")
            .contains("Extractor_post phase — ")
            .contains("Post phase — ")
            .contains("Output phase — ");
    }

    @Test
    void debugTraceShowsLookingForStepHeader() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.2020.mkv", trace);
        assertThat(sw.toString()).contains("  Looking for year");
    }

    @Test
    void spansEmittedWhenToggleEnabled() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw, true);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.2020.mkv", trace);
        // The input string appears at least once embedded in a span view block.
        assertThat(sw.toString()).contains("Movie.2020.mkv");
        // Per-row underline must appear under at least one step.
        assertThat(sw.toString()).contains("─");
    }
}
