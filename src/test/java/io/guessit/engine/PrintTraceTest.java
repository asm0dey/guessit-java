package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrintTraceTest {

    @Test
    void formatsBareMatchValueStartEndName() {
        var m = Match.of("year", 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).isEqualTo("2020:(11,15)+name=year");
    }

    @Test
    void includesPrivateBeforeName() {
        var m = new Match("weak_episode", 2020, 11, 15, "2020", 1000, Set.of(), true);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("2020:(11,15)+private+name=weak_episode");
    }

    @Test
    void includesPriorityWhenNotDefault() {
        var m = Match.of("source", "Blu-ray", 22, 28, "Blu-ray").withPriority(2048);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("Blu-ray:(22,28)+name=source+priority=2048");
    }

    @Test
    void omitsPriorityAtDefault() {
        var m = Match.of("year", 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("priority=");
    }

    @Test
    void includesTagsWhenPresent() {
        var tags = new java.util.LinkedHashSet<String>();
        tags.add("weak-episode");
        tags.add("weak-duplicate");
        var m = Match.of("season", 20, 11, 13, "20").withTags(tags);
        var formatted = PrintTrace.formatMatch(m);
        assertThat(formatted)
            .startsWith("20:(11,13)+name=season+tags=[")
            .endsWith("]");
        assertThat(formatted).contains("weak-episode");
        assertThat(formatted).contains("weak-duplicate");
    }

    @Test
    void omitsTagsWhenEmpty() {
        var m = Match.of("year", 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("tags=");
    }
}
