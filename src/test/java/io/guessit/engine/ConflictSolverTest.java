package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.guessit.engine.MatchName.*;

class ConflictSolverTest {

    @Test
    void higherPriorityWinsOverlap() {
        var s = new MatchSet();
        s.add(Match.of(YEAR, 2020, 0, 4, "2020").withPriority(2000));
        s.add(Match.of(SEASON, 20, 0, 2, "20").withPriority(1000));
        ConflictSolver.solve(s);
        var names = s.all().map(Match::name).toList();
        assertEquals(List.of(YEAR), names);
    }

    @Test
    void longerWinsOnTiePriority() {
        var s = new MatchSet();
        s.add(Match.of(MatchName.OTHER, 1, 0, 4, "abcd"));
        s.add(Match.of(MatchName.OTHER, 2, 0, 2, "ab"));
        ConflictSolver.solve(s);
        assertEquals(List.of(MatchName.OTHER), s.all().map(Match::name).toList());
    }

    @Test
    void earlierStartWinsOnTiePriorityAndLength() {
        var s = new MatchSet();
        s.add(Match.of(MatchName.OTHER, 1, 2, 4, "ab"));
        s.add(Match.of(MatchName.OTHER, 2, 0, 2, "cd"));
        ConflictSolver.solve(s);
        assertEquals(2, s.all().count());
    }

    @Test
    void coexistTagSurvives() {
        var s = new MatchSet();
        s.add(Match.of(COUNTRY, "FR", 0, 2, "FR").withPriority(1000));
        s.add(Match.of(LANGUAGE, "fr", 0, 2, "fr").withPriority(1000).withTags(java.util.Set.of("coexist")));
        ConflictSolver.solve(s);
        assertEquals(2, s.all().count());
    }

    @Test
    void noOverlapKeepsAll() {
        var s = new MatchSet();
        s.add(Match.of(MatchName.OTHER, 1, 0, 2, "ab"));
        s.add(Match.of(MatchName.OTHER, 2, 5, 7, "cd"));
        ConflictSolver.solve(s);
        assertEquals(2, s.all().count());
    }
}
