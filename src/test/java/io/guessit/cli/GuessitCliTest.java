package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GuessitCliTest {

    @Test
    void plainOutputContainsInput() {
        var captured = run("Movie.Name.2020.1080p.mkv");
        assertNotNull(captured);
    }

    @Test
    void jsonOutputIsValidJsonObject() {
        var out = run("--json", "Movie.Name.2020.1080p.mkv");
        var trimmed = out.trim();
        assertThat(trimmed)
                .as("expected JSON object, got: " + trimmed)
                .startsWith("{")
                .endsWith("}");
    }

    @Test
    void yamlOutputDoesNotThrow() {
        var out = run("--yaml", "Movie.Name.2020.1080p.mkv");
        assertNotNull(out);
    }

    @Test
    void helpExits0() {
        int code = new CommandLine(new GuessitCli()).execute("--help");
        assertThat(code).isZero();
    }

    @Test
    void versionExits0() {
        int code = new CommandLine(new GuessitCli()).execute("--version");
        assertThat(code).isZero();
    }

    private String run(String... args) {
        var baos = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new GuessitCli()).execute(args);
            assertEquals(0, code, "exit code");
        } finally {
            System.setOut(prev);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
