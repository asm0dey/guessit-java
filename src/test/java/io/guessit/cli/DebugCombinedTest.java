package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DebugCombinedTest {

    @Test
    void verboseAndDebugAreMutuallyExclusive() {
        var bo = new ByteArrayOutputStream();
        var be = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new CommandLine(new GuessitCli()).execute("-v", "--debug",
                "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        assertThat(code).isEqualTo(2);
        assertThat(be.toString(StandardCharsets.UTF_8))
            .contains("-v/--verbose and --debug are mutually exclusive");
    }
}
