package io.guessit.engine;

import io.guessit.GuessResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * {@link Trace} that emits human-readable prose to an {@link Appendable}.
 *
 * <p>Used by the CLI when {@code --debug} is set; library consumers can
 * compose with {@link CompositeTrace} to combine prose narration with the
 * machine {@link PrintTrace} format.
 *
 * <p>Indentation:
 * <ul>
 *   <li>Phase header: column 0</li>
 *   <li>Step header (extractor/processor/marker): column 2</li>
 *   <li>Sub-step / span view: column 4 (span view itself indents +2 internally)</li>
 * </ul>
 */
public final class DebugTrace implements Trace {

    private final Appendable out;
    private final boolean renderSpans;

    public DebugTrace(Appendable out) { this(out, false); }
    public DebugTrace(Appendable out, boolean renderSpans) {
        this.out = out;
        this.renderSpans = renderSpans;
    }

    @Override public void input(String s) {
        write("For: " + s + "\n\n");
    }

    @Override public void phase(String name) {
        write(capitalise(name) + " phase\n");
    }

    @Override public void phase(String name, String description) {
        write(capitalise(name) + " phase — " + description + "\n");
    }

    @Override public void step(String kind, String name) {
        write("  " + verb(kind) + " " + name + "\n");
    }

    @Override public void step(String kind, String name, String description) {
        if (description == null || description.isEmpty() || description.equals(name)) {
            step(kind, name);
            return;
        }
        write("  " + verb(kind) + " " + name + " (" + description + ")\n");
    }

    @Override public void subStep(String message) {
        write("    " + message + "\n");
    }

    @Override public void noChanges() {
        write("    (no changes)\n");
    }

    @Override public void note(String msg) {
        // Legacy machine-format channel (used by PrintTrace for marker lines).
        // DebugTrace uses subStep for prose narration; ignore note() to avoid
        // duplicating the marker line in --debug output.
    }

    @Override public void spans(String input, List<Match> matches, List<Marker> markers) {
        if (!renderSpans) return;
        var rendered = SpanRenderer.render(input, matches, markers);
        // Sub-step indent (4 spaces) on every non-empty line.
        for (var line : rendered.split("\n", -1)) {
            if (line.isEmpty()) {
                write("\n");
            } else {
                write("    " + line + "\n");
            }
        }
    }

    @Override public void result(GuessResult r) {
        // Result content goes to stdout via the formatter; DebugTrace just emits
        // a closing marker so the narration block is visually terminated.
        write("\nGuessIt parsed.\n");
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Step kind → verb mapping for the prose header. */
    private static String verb(String kind) {
        return switch (kind) {
            case "extract" -> "Looking for";
            case "post"    -> "Refining";
            case "rule"    -> "Running rule";
            case "marker"  -> "Detecting";
            default        -> "Step (" + kind + "):";
        };
    }

    private void write(String s) {
        try { out.append(s); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
