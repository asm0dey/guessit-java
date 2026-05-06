package io.guessit.engine;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.engine.Chain.Repeater.PLUS;
import static io.guessit.engine.Chain.Repeater.STAR;
import static java.util.List.of;
import static java.util.regex.Pattern.compile;
import static org.assertj.core.api.Assertions.assertThat;

class ChainTest {
    @Test void singleHeadSingleTail() {
        var head = compile("(?i)s(?<season>\\d+)e(?<episode>\\d+)");
        var tail = compile("(?i)-?e(?<episode>\\d+)");
        var runs = new Chain(head).tail(tail, STAR).scan("S01E02-E03");
        Assertions.assertThat(runs).hasSize(1);
        var run = runs.getFirst();
        assertThat(run.start()).isZero();
        assertThat(run.end()).isEqualTo(10);
        assertThat(run.captures("season")).isEqualTo(of("01"));
        assertThat(run.captures("episode")).isEqualTo(of("02", "03"));
    }
    @Test void plusRequiresAtLeastOneTail() {
        var head = compile("(?i)(?<season>\\d+)x(?<episode>\\d+)");
        var tail = compile("(?i)\\s+(?<season>\\d+)x(?<episode>\\d+)");
        assertThat(new Chain(head).tail(tail, PLUS).scan("01x02")).isEmpty();
        Assertions.assertThat(new Chain(head).tail(tail, PLUS).scan("01x02 03x04")).hasSize(1);
    }
    @Test void noOverlap() {
        var head = compile("\\d");
        var runs = new Chain(head).scan("abc1def2ghi");
        Assertions.assertThat(runs).hasSize(2);
        assertThat(runs.get(0).start()).isEqualTo(3);
        assertThat(runs.get(1).start()).isEqualTo(7);
    }
}
