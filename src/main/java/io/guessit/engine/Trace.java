package io.guessit.engine;

import io.guessit.GuessResult;

import java.util.List;

/**
 * Sink for verbose pipeline trace events. All methods default to no-ops so
 * phases can call into the trace unconditionally without paying a cost when
 * verbose mode is disabled. CLI {@code -v} attaches a {@link PrintTrace};
 * CLI {@code --debug} attaches a DebugTrace; both can coexist via
 * CompositeTrace.
 */
public interface Trace {
    Trace NOOP = new Trace() {};

    default void input(String s) {}
    default void phase(String name) {}
    /** Phase header with a human-readable description. Default delegates to {@link #phase(String)}. */
    default void phase(String name, String description) { phase(name); }
    default void step(String kind, String name) {}
    /** Step header with a human-readable description. Default delegates to {@link #step(String,String)}. */
    default void step(String kind, String name, String description) { step(kind, name); }
    default void added(Match m) {}
    default void removed(Match m) {}
    default void noChanges() {}
    default void note(String msg) {}
    /** Generic indented sub-event emitted from inside a step (e.g. PatternMatcher
     *  tries, ConflictSolver pair decisions, processor sub-stages, per-property
     *  output assignments). */
    default void subStep(String message) {}
    /** Snapshot of all live spans (non-private matches + markers) plus the input,
     *  emitted by phases when the match set changed within a step. DebugTrace
     *  with span rendering enabled paints an ASCII view; everything else no-ops. */
    default void spans(String input, List<Match> matches, List<Marker> markers) {}
    default void result(GuessResult r) {}
}
