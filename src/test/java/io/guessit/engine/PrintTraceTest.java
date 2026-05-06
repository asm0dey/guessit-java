package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static io.guessit.engine.MatchName.*;

class PrintTraceTest {

    @Test
    void formatsBareMatchValueStartEndName() {
        var m = Match.of(YEAR, 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).isEqualTo("2020:(11,15)+name=year");
    }

    @Test
    void includesPrivateBeforeName() {
        var m = new Match(MatchName.WEAK, 2020, 11, 15, "2020", 1000, Set.of(), true);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("2020:(11,15)+private+name=weak");
    }

    @Test
    void includesPriorityWhenNotDefault() {
        var m = Match.of(SOURCE, "Blu-ray", 22, 28, "Blu-ray").withPriority(2048);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("Blu-ray:(22,28)+name=source+priority=2048");
    }

    @Test
    void omitsPriorityAtDefault() {
        var m = Match.of(YEAR, 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("priority=");
    }

    @Test
    void rendersTagsAlphabeticallySorted() {
        var tags = new java.util.LinkedHashSet<String>();
        tags.add("weak-episode");
        tags.add("weak-duplicate");
        var m = Match.of(SEASON, 20, 11, 13, "20").withTags(tags);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("20:(11,13)+name=season+tags=[weak-duplicate,weak-episode]");
    }

    @Test
    void omitsTagsWhenEmpty() {
        var m = Match.of(YEAR, 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("tags=");
    }

    @Test
    void inputLineFollowedByBlank() {
        var sb = new StringBuilder();
        new PrintTrace(sb).input("Movie.Name.2020.mkv");
        assertThat(sb.toString()).isEqualTo("For: Movie.Name.2020.mkv\n\n");
    }

    @Test
    void phaseLineHeader() {
        var sb = new StringBuilder();
        new PrintTrace(sb).phase("extractors");
        assertThat(sb.toString()).isEqualTo("[phase] extractors\n");
    }

    @Test
    void stepLineIndentedTwoSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).step("extract", "year");
        assertThat(sb.toString()).isEqualTo("  [extract] year\n");
    }

    @Test
    void addedLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        var m = Match.of(YEAR, 2020, 11, 15, "2020");
        new PrintTrace(sb).added(m);
        assertThat(sb.toString()).isEqualTo("    + 2020:(11,15)+name=year\n");
    }

    @Test
    void removedLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        var m = Match.of(YEAR, 2020, 11, 15, "2020");
        new PrintTrace(sb).removed(m);
        assertThat(sb.toString()).isEqualTo("    - 2020:(11,15)+name=year\n");
    }

    @Test
    void noChangesLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).noChanges();
        assertThat(sb.toString()).isEqualTo("    (no changes)\n");
    }

    @Test
    void noteLineIndentedTwoSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).note("marker: path:(0,41)+name=path");
        assertThat(sb.toString()).isEqualTo("  marker: path:(0,41)+name=path\n");
    }
}
