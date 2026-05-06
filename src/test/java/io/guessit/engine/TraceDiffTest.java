package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static io.guessit.engine.MatchName.*;

class TraceDiffTest {

    static class CapturingTrace implements Trace {
        final List<String> events = new ArrayList<>();
        @Override public void added(Match m)   { events.add("+ " + PrintTrace.formatMatch(m)); }
        @Override public void removed(Match m) { events.add("- " + PrintTrace.formatMatch(m)); }
        @Override public void noChanges() { events.add("(no changes)"); }
    }

    @Test
    void emitsAddedForMatchPresentOnlyInAfter() {
        var year = Match.of(YEAR, 2020, 11, 15, "2020");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(), List.of(year), trace);
        assertThat(trace.events).containsExactly("+ 2020:(11,15)+name=year");
    }

    @Test
    void emitsRemovedForMatchPresentOnlyInBefore() {
        var year = Match.of(YEAR, 2020, 11, 15, "2020");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(year), List.of(), trace);
        assertThat(trace.events).containsExactly("- 2020:(11,15)+name=year");
    }

    @Test
    void emitsNoChangesWhenIdentical() {
        var year = Match.of(YEAR, 2020, 11, 15, "2020");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(year), List.of(year), trace);
        assertThat(trace.events).containsExactly("(no changes)");
    }

    @Test
    void emitsRemovesBeforeAdds() {
        var year = Match.of(YEAR, 2020, 11, 15, "2020");
        var screen = Match.of(SCREEN_SIZE, "1080p", 16, 21, "1080p");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(year), List.of(screen), trace);
        assertThat(trace.events).containsExactly(
            "- 2020:(11,15)+name=year",
            "+ 1080p:(16,21)+name=screen_size"
        );
    }

    @Test
    void preservesAfterOrderForAdds() {
        var a = Match.of(YEAR, 2020, 11, 15, "2020");
        var b = Match.of(SCREEN_SIZE, "1080p", 16, 21, "1080p");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(), List.of(a, b), trace);
        assertThat(trace.events).containsExactly(
            "+ 2020:(11,15)+name=year",
            "+ 1080p:(16,21)+name=screen_size"
        );
    }
}
