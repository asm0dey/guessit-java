package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DebugCombinedTest {

    @Test
    void verboseAndDebugBothEmitConcurrently() {
        var bo = new ByteArrayOutputStream();
        var be = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new GuessitCli()).execute("-v", "--debug",
                "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");
            assertThat(code).isZero();
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }

        var stdout = bo.toString(StandardCharsets.UTF_8);
        var stderr = be.toString(StandardCharsets.UTF_8);

        // Machine trace on stdout (existing -v contract):
        assertThat(stdout).contains("[phase] extractors");
        assertThat(stdout).contains("[extract] year");
        assertThat(stdout).contains("+ 2020:(11,15)+name=year");

        // Prose narration on stderr (new --debug contract):
        assertThat(stderr).contains("Extractors phase — ");
        assertThat(stderr).contains("Looking for year");
        assertThat(stderr).contains("Considered '2020' at 11-15 — accepted");
    }
}
