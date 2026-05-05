package io.guessit.engine;

import io.guessit.GuessResult;

/**
 * Sink for verbose pipeline trace events. All methods default to no-ops so
 * phases can call into the trace unconditionally without paying a cost when
 * verbose mode is disabled. CLI {@code -v} attaches a {@link PrintTrace}.
 */
public interface Trace {
    Trace NOOP = new Trace() {};

    default void input(String s) {}
    default void phase(String name) {}
    default void step(String kind, String name) {}
    default void added(Match m) {}
    default void removed(Match m) {}
    default void note(String msg) {}
    default void result(GuessResult r) {}
}
