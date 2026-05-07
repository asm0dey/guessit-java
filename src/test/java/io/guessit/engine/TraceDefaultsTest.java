package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDefaultsTest {

    @Test
    void noopAcceptsAllNewMethods() {
        Trace t = Trace.NOOP;
        // None of these should throw.
        t.subStep("anything");
        t.step("kind", "name", "description");
        t.phase("name", "description");
        t.spans("input", List.of(), List.of());
        assertThat(t).isNotNull();
    }

    @Test
    void threeArgStepDelegatesToTwoArgWhenNotOverridden() {
        var seen = new java.util.ArrayList<String>();
        Trace t = new Trace() {
            @Override public void step(String kind, String name) { seen.add(kind + ":" + name); }
        };
        t.step("post", "year", "describe me");
        assertThat(seen).containsExactly("post:year");
    }

    @Test
    void twoArgPhaseDelegateFromThreeArg() {
        var seen = new java.util.ArrayList<String>();
        Trace t = new Trace() {
            @Override public void phase(String name) { seen.add(name); }
        };
        t.phase("markers", "describe me");
        assertThat(seen).containsExactly("markers");
    }
}
