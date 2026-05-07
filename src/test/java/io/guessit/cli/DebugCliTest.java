package io.guessit.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DebugCliTest {

    @Test
    void debugProseGoesToStderrByDefault() {
        var out = runCapture(new String[]{"--debug", "Movie.2020.1080p.mkv"});
        assertThat(out.stderr()).contains("For: Movie.2020.1080p.mkv");
        assertThat(out.stderr()).contains("Looking for year");
        assertThat(out.stdout()).doesNotContain("Looking for");
    }

    @Test
    void debugWithJsonKeepsStdoutClean() {
        var out = runCapture(new String[]{"--debug", "--json", "Movie.2020.mkv"});
        assertThat(out.stderr()).contains("Looking for year");
        assertThat(out.stdout()).startsWith("{");
        assertThat(out.stderr()).doesNotContain("ignored when --verbose");
    }

    @Test
    void debugOutWritesToFile(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("trace.txt");
        runCapture(new String[]{"--debug", "--debug-out", f.toString(), "Movie.2020.mkv"});
        var contents = Files.readString(f, StandardCharsets.UTF_8);
        assertThat(contents).contains("For: Movie.2020.mkv");
        assertThat(contents).contains("Looking for year");
    }

    @Test
    void debugMarkersWithoutDebugErrors() {
        var out = runCapture(new String[]{"--debug-markers", "Movie.2020.mkv"});
        assertThat(out.exit()).isEqualTo(2);
        assertThat(out.stderr()).contains("--debug-markers requires --debug");
    }

    @Test
    void debugMarkersRendersSpanView() {
        var out = runCapture(new String[]{"--debug", "--debug-markers", "XxX.2020.mkv"});
        assertThat(out.stderr()).contains("XxX.2020.mkv");
        assertThat(out.stderr()).contains("year");
        // Box-drawing underline char (─, U+2500) should be present in marker view
        assertThat(out.stderr()).contains("─");
    }

    @Test
    void multipleFilenamesSeparatedByBlankLine() {
        var out = runCapture(new String[]{"--debug", "A.2020.mkv", "B.2021.mkv"});
        assertThat(out.stderr()).contains("For: A.2020.mkv");
        assertThat(out.stderr()).contains("For: B.2021.mkv");
        assertThat(out.stderr()).contains("\n\nFor: B.2021.mkv");
    }

    private record Captured(int exit, String stdout, String stderr) {}

    private Captured runCapture(String[] args) {
        var bo = new ByteArrayOutputStream();
        var be = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new CommandLine(new GuessitCli()).execute(args);
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        return new Captured(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
    }
}
