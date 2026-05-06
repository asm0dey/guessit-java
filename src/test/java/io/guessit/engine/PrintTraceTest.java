package io.guessit.engine;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static io.guessit.engine.Match.of;
import static io.guessit.engine.MatchName.*;
import static io.guessit.engine.PrintTrace.formatMatch;
import static org.assertj.core.api.Assertions.assertThat;

class PrintTraceTest {

    @Test
    void formatsBareMatchValueStartEndName() {
        var m = of(YEAR, 2020, 11, 15, "2020");
        Assertions.assertThat(formatMatch(m)).isEqualTo("2020:(11,15)+name=year");
    }

    @Test
    void includesPrivateBeforeName() {
        var m = new Match(WEAK, 2020, 11, 15, "2020", 1000, Set.of(), true);
        Assertions.assertThat(formatMatch(m)).isEqualTo("2020:(11,15)+private+name=weak");
    }

    @Test
    void includesPriorityWhenNotDefault() {
        var m = of(SOURCE, "Blu-ray", 22, 28, "Blu-ray").withPriority(2048);
        Assertions.assertThat(formatMatch(m)).isEqualTo("Blu-ray:(22,28)+name=source+priority=2048");
    }

    @Test
    void omitsPriorityAtDefault() {
        var m = Match.of(YEAR, 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("priority=");
    }

    @Test
    void rendersTagsAlphabeticallySorted() {
        var tags = new LinkedHashSet<String>();
        tags.add("weak-episode");
        tags.add("weak-duplicate");
        var m = of(SEASON, 20, 11, 13, "20").withTags(tags);
        Assertions.assertThat(formatMatch(m)).isEqualTo("20:(11,13)+name=season+tags=[weak-duplicate,weak-episode]");
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
        Assertions.assertThat(sb.toString()).isEqualTo("For: Movie.Name.2020.mkv\n\n");
    }

    @Test
    void phaseLineHeader() {
        var sb = new StringBuilder();
        new PrintTrace(sb).phase("extractors");
        Assertions.assertThat(sb.toString()).isEqualTo("[phase] extractors\n");
    }

    @Test
    void stepLineIndentedTwoSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).step("extract", "year");
        Assertions.assertThat(sb.toString()).isEqualTo("  [extract] year\n");
    }

    @Test
    void addedLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        var m = of(YEAR, 2020, 11, 15, "2020");
        new PrintTrace(sb).added(m);
        Assertions.assertThat(sb.toString()).isEqualTo("    + 2020:(11,15)+name=year\n");
    }

    @Test
    void removedLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        var m = of(YEAR, 2020, 11, 15, "2020");
        new PrintTrace(sb).removed(m);
        Assertions.assertThat(sb.toString()).isEqualTo("    - 2020:(11,15)+name=year\n");
    }

    @Test
    void noChangesLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).noChanges();
        Assertions.assertThat(sb.toString()).isEqualTo("    (no changes)\n");
    }

    @Test
    void noteLineIndentedTwoSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).note("marker: path:(0,41)+name=path");
        Assertions.assertThat(sb.toString()).isEqualTo("  marker: path:(0,41)+name=path\n");
    }
}
