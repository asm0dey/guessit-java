package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.guessit.engine.ConflictSolver.solve;
import static io.guessit.engine.Match.of;
import static io.guessit.engine.MatchName.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConflictSolverTest {

    @Test
    void higherPriorityWinsOverlap() {
        var s = new MatchSet();
        s.add(of(YEAR, 2020, 0, 4, "2020").withPriority(2000));
        s.add(of(SEASON, 20, 0, 2, "20").withPriority(1000));
        solve(s);
        var names = s.all().map(Match::name).toList();
        assertThat(names).isEqualTo(List.of(YEAR));
    }

    @Test
    void longerWinsOnTiePriority() {
        var s = new MatchSet();
        s.add(of(OTHER, 1, 0, 4, "abcd"));
        s.add(of(OTHER, 2, 0, 2, "ab"));
        solve(s);
        assertThat(s.all().map(Match::name).toList()).isEqualTo(List.of(OTHER));
    }

    @Test
    void earlierStartWinsOnTiePriorityAndLength() {
        var s = new MatchSet();
        s.add(Match.of(OTHER, 1, 2, 4, "ab"));
        s.add(Match.of(OTHER, 2, 0, 2, "cd"));
        ConflictSolver.solve(s);
        assertThat(s.all().count()).isEqualTo(2);
    }

    @Test
    void coexistTagSurvives() {
        var s = new MatchSet();
        s.add(Match.of(COUNTRY, "FR", 0, 2, "FR").withPriority(1000));
        s.add(Match.of(LANGUAGE, "fr", 0, 2, "fr").withPriority(1000).withTags(Set.of("coexist")));
        ConflictSolver.solve(s);
        assertThat(s.all().count()).isEqualTo(2);
    }

    @Test
    void noOverlapKeepsAll() {
        var s = new MatchSet();
        s.add(Match.of(OTHER, 1, 0, 2, "ab"));
        s.add(Match.of(OTHER, 2, 5, 7, "cd"));
        ConflictSolver.solve(s);
        assertThat(s.all().count()).isEqualTo(2);
    }
}
