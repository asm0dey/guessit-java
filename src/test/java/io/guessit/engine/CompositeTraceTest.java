package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeTraceTest {

    @Test
    void fanOutsToEverySink() {
        var a = new RecordingTrace();
        var b = new RecordingTrace();
        Trace t = new CompositeTrace(a, b);
        t.input("hello");
        t.phase("markers");
        t.subStep("found x");
        assertThat(a.events).containsExactly("input:hello", "phase:markers", "subStep:found x");
        assertThat(b.events).containsExactly("input:hello", "phase:markers", "subStep:found x");
    }

    @Test
    void preservesSinkOrder() {
        var seen = new ArrayList<String>();
        Trace a = new Trace() { @Override public void note(String m) { seen.add("a"); } };
        Trace b = new Trace() { @Override public void note(String m) { seen.add("b"); } };
        new CompositeTrace(a, b).note("x");
        assertThat(seen).containsExactly("a", "b");
    }

    @Test
    void exceptionInOneSinkDoesNotSkipOthers() {
        var b = new RecordingTrace();
        Trace a = new Trace() { @Override public void note(String m) { throw new RuntimeException("boom"); } };
        var t = new CompositeTrace(a, b);
        t.note("x");
        assertThat(b.events).containsExactly("note:x");
    }

    private static final class RecordingTrace implements Trace {
        final List<String> events = new ArrayList<>();
        @Override public void input(String s) { events.add("input:" + s); }
        @Override public void phase(String name) { events.add("phase:" + name); }
        @Override public void subStep(String m) { events.add("subStep:" + m); }
        @Override public void note(String m) { events.add("note:" + m); }
    }
}
