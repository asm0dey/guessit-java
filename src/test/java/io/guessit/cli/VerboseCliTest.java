package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VerboseCliTest {

    @Test
    void verboseOutputContainsAllPhasesAndKnownEvents() {
        var out = run("-v", "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");

        assertThat(out)
            .contains("For: Movie.Name.2020.1080p.BluRay.x264-GRP.mkv")
            .contains("[phase] markers")
            .contains("[phase] extractors")
            .contains("[phase] conflicts")
            .contains("[phase] extractor_post")
            .contains("[phase] post")
            .contains("[phase] output")
            .contains("GuessIt found:");
    }

    @Test
    void verboseShowsKnownExtractorStepAndAddedMatch() {
        var out = run("-v", "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");

        assertThat(out).contains("[extract] year");
        assertThat(out).contains("+ 2020:(11,15)+name=year");
        assertThat(out).contains("[extract] screen_size");
        assertThat(out).contains("+ 1080p:(16,21)+name=screen_size");
    }

    @Test
    void verboseSuppressesJsonOutputAndWarnsOnStderr() {
        var baos = new ByteArrayOutputStream();
        var berr = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(berr, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new GuessitCli()).execute("-v", "--json",
                "Movie.Name.2020.1080p.mkv");
            assertThat(code).isZero();
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }

        var stdout = baos.toString(StandardCharsets.UTF_8);
        var stderr = berr.toString(StandardCharsets.UTF_8);

        assertThat(stderr).contains("warning: --json/--yaml/--show-property ignored when --verbose is set");
        assertThat(stdout).doesNotContain("\"title\":");
    }

    @Test
    void verboseTwoFilenamesAreSeparatedByBlankLine() {
        var out = run("-v",
            "Movie.A.2020.mkv",
            "Movie.B.2021.mkv");
        assertThat(out).contains("For: Movie.A.2020.mkv");
        assertThat(out).contains("For: Movie.B.2021.mkv");
        // The second filename's "For: " header must be preceded by a blank line.
        assertThat(out).contains("\n\nFor: Movie.B.2021.mkv");
    }

    private String run(String... args) {
        var baos = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            new CommandLine(new GuessitCli()).execute(args);
        } finally {
            System.setOut(prev);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
