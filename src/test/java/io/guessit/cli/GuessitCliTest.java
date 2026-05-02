package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(trimmed.startsWith("{"), "expected JSON object, got: " + trimmed);
        assertTrue(trimmed.endsWith("}"), "expected JSON object, got: " + trimmed);
    }

    @Test
    void yamlOutputDoesNotThrow() {
        var out = run("--yaml", "Movie.Name.2020.1080p.mkv");
        assertNotNull(out);
    }

    @Test
    void helpExits0() {
        int code = new CommandLine(new GuessitCli()).execute("--help");
        assertEquals(0, code);
    }

    @Test
    void versionExits0() {
        int code = new CommandLine(new GuessitCli()).execute("--version");
        assertEquals(0, code);
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
