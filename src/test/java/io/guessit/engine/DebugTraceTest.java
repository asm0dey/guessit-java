package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugTraceTest {

    @Test
    void inputHeader() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.input("Movie.2020.mkv");
        assertThat(sb.toString()).isEqualTo("For: Movie.2020.mkv\n\n");
    }

    @Test
    void phaseHeaderWithDescription() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.phase("extractors", "scanning input for property patterns");
        assertThat(sb.toString()).isEqualTo("Extractors phase — scanning input for property patterns\n");
    }

    @Test
    void phaseHeaderWithoutDescriptionFallsBackToCapitalisedName() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.phase("conflicts");
        assertThat(sb.toString()).isEqualTo("Conflicts phase\n");
    }

    @Test
    void stepUsesDescription() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.step("extract", "year", "4-digit year (19xx/20xx)");
        assertThat(sb.toString()).isEqualTo("  Looking for year (4-digit year (19xx/20xx))\n");
    }

    @Test
    void stepWithoutDescriptionOmitsParenthetical() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.step("extract", "year");
        assertThat(sb.toString()).isEqualTo("  Looking for year\n");
    }

    @Test
    void stepWithDescriptionEqualToNameOmitsParenthetical() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.step("extract", "year", "year");
        assertThat(sb.toString()).isEqualTo("  Looking for year\n");
    }

    @Test
    void substepIndentsByFour() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.subStep("Trying regex \\d{4}");
        assertThat(sb.toString()).isEqualTo("    Trying regex \\d{4}\n");
    }

    @Test
    void noChangesEmitted() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.noChanges();
        assertThat(sb.toString()).isEqualTo("    (no changes)\n");
    }

    @Test
    void resultPrintsClosingMarker() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        var r = io.guessit.GuessResultBuilder.result().title("Foo").build();
        t.result(r);
        assertThat(sb.toString()).isEqualTo("\nGuessIt parsed.\n");
    }

    @Test
    void spansNotRenderedWhenToggleDisabled() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.spans("XxX.2020.mkv", List.of(Match.of(MatchName.YEAR, 2020, 4, 8, "2020")), List.of());
        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void spansRenderedWhenToggleEnabled() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb, true);
        t.spans("XxX.2020.mkv", List.of(Match.of(MatchName.YEAR, 2020, 4, 8, "2020")), List.of());
        assertThat(sb.toString()).contains("XxX.2020.mkv");
        assertThat(sb.toString()).contains("year");
    }
}
