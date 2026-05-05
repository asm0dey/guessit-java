package io.guessit.engine;

import io.guessit.GuessResult;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@link Trace} that writes formatted lines to an {@link Appendable}. Used by
 * the CLI when {@code -v} is set; not part of the documented library API.
 */
public final class PrintTrace implements Trace {

    private final Appendable out;

    public PrintTrace(Appendable out) { this.out = out; }

    static String formatMatch(Match m) {
        var sb = new StringBuilder();
        sb.append(m.raw()).append(':').append('(').append(m.start()).append(',').append(m.end()).append(')');
        if (m.isPrivate()) sb.append("+private");
        sb.append("+name=").append(m.name());
        if (m.priority() != 1000) sb.append("+priority=").append(m.priority());
        if (!m.tags().isEmpty()) {
            sb.append("+tags=[");
            boolean first = true;
            for (var t : m.tags()) {
                if (!first) sb.append(',');
                sb.append(t);
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @Override public void input(String s)    { write("For: " + s + "\n\n"); }
    @Override public void phase(String name) { write("[phase] " + name + "\n"); }
    @Override public void step(String kind, String name) { write("  [" + kind + "] " + name + "\n"); }
    @Override public void added(Match m)     { write("    + " + formatMatch(m) + "\n"); }
    @Override public void removed(Match m)   { write("    - " + formatMatch(m) + "\n"); }
    @Override public void noChanges() { write("    (no changes)\n"); }
    @Override public void note(String msg)   { write("  " + msg + "\n"); }
    @Override public void result(GuessResult r) {
        write("\nGuessIt found:\n" + io.guessit.cli.PlainFormatter.format(r) + "\n");
    }

    private void write(String s) {
        try { out.append(s); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
