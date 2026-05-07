package io.guessit.engine;

import io.guessit.GuessResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * Fan-out {@link Trace} that forwards every event to a fixed list of sinks.
 *
 * <p>Per-sink exceptions are caught so a misbehaving sink cannot break the
 * other sinks or the parse itself. Sinks are invoked in the order supplied to
 * the constructor.
 */
public final class CompositeTrace implements Trace {

    private final List<Trace> sinks;

    public CompositeTrace(Trace... sinks) { this.sinks = List.of(sinks); }
    public CompositeTrace(List<Trace> sinks) { this.sinks = List.copyOf(sinks); }

    private void forEach(Consumer<Trace> action) {
        for (var s : sinks) {
            try { action.accept(s); } catch (RuntimeException ignored) { /* best-effort */ }
        }
    }

    @Override public void input(String s)                            { forEach(t -> t.input(s)); }
    @Override public void phase(String name)                         { forEach(t -> t.phase(name)); }
    @Override public void phase(String name, String description)     { forEach(t -> t.phase(name, description)); }
    @Override public void step(String kind, String name)             { forEach(t -> t.step(kind, name)); }
    @Override public void step(String kind, String name, String d)   { forEach(t -> t.step(kind, name, d)); }
    @Override public void added(Match m)                             { forEach(t -> t.added(m)); }
    @Override public void removed(Match m)                           { forEach(t -> t.removed(m)); }
    @Override public void noChanges()                                { forEach(Trace::noChanges); }
    @Override public void note(String msg)                           { forEach(t -> t.note(msg)); }
    @Override public void subStep(String msg)                        { forEach(t -> t.subStep(msg)); }
    @Override public void spans(String in, List<Match> ms, List<Marker> mk) { forEach(t -> t.spans(in, ms, mk)); }
    @Override public void result(GuessResult r)                      { forEach(t -> t.result(r)); }
}
