package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictSolverDebugTest {

    @Test
    void emitsDropDecisionForShorterSpan() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var ms = new MatchSet();
        ms.add(Match.of(MatchName.YEAR, 2020, 4, 8, "2020"));         // length 4
        ms.add(Match.of(MatchName.SCREEN_SIZE, "x", 6, 14, "y1080p"));// length 8, overlaps year
        ConflictSolver.solve(ms, tr);
        assertThat(fired).anyMatch(s -> s.startsWith("Dropping ") && s.contains("overlaps") && s.contains("shorter span"));
    }

    @Test
    void emitsNothingWhenNoOverlap() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var ms = new MatchSet();
        ms.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        ms.add(Match.of(MatchName.SCREEN_SIZE, "1080p", 5, 10, "1080p"));
        ConflictSolver.solve(ms, tr);
        assertThat(fired).isEmpty();
    }

    @Test
    void backwardsCompatibleNoTraceOverloadStillWorks() {
        var ms = new MatchSet();
        ms.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        ConflictSolver.solve(ms);
        assertThat(ms.snapshot()).hasSize(1);
    }
}
