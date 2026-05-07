package io.guessit.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders an input string with per-underline-row underline + one label
 * line immediately below, using box-drawing characters.
 *
 * <p>Layout rules:
 * <ul>
 *   <li>Both match and marker span underlines use {@code ─} (U+2500) body
 *       with {@code ┬} (U+252C) at the midpoint.</li>
 *   <li>Single-char span: {@code │} (U+2502) at that column.</li>
 *   <li>Spans share an underline row only when both their bodies and their
 *       centered label extents are non-overlapping (≥1 column gap between
 *       each pair).  This guarantees labels fit collision-free on the single
 *       label line immediately below each underline row.</li>
 *   <li>No leading indent — output starts at column 0.</li>
 *   <li>Trailing whitespace stripped per line.</li>
 * </ul>
 */
public final class SpanRenderer {

    // Box-drawing constants
    private static final char HORIZ = '─'; // U+2500
    private static final char TEE   = '┬'; // U+252C
    private static final char VERT  = '│'; // U+2502

    private SpanRenderer() {}

    public static String render(String input, List<Match> matches, List<Marker> markers) {
        record Span(int start, int end, String label) {
            int mid() { return start + (end - start) / 2; }
            int len() { return end - start; }
            /** Inclusive start of the centered label. */
            int labelStart() {
                int half = label.length() / 2;
                return Math.max(0, mid() - half);
            }
            /** Exclusive end of the centered label. */
            int labelEnd() { return labelStart() + label.length(); }
        }

        var spans = new ArrayList<Span>();
        for (var m : matches) {
            if (m.isPrivate()) continue;
            spans.add(new Span(m.start(), m.end(), m.name().name().toLowerCase(Locale.ROOT)));
        }
        for (var mk : markers) {
            spans.add(new Span(mk.start(), mk.end(), mk.name()));
        }

        if (spans.isEmpty()) {
            return input + "\n";
        }

        spans.sort(Comparator.<Span>comparingInt(Span::start).thenComparingInt(Span::end));

        // ── Step 1: assign each span to an underline row ─────────────────────
        // Two spans can share a row only when both their bodies and their label
        // extents are non-overlapping (strict gap ≥ 1 column in both cases).
        var underlineRows = new ArrayList<List<Span>>();
        for (var s : spans) {
            int placed = -1;
            for (int r = 0; r < underlineRows.size(); r++) {
                boolean fits = true;
                for (var ex : underlineRows.get(r)) {
                    // body overlap check: touching or overlapping → no fit
                    boolean bodyOverlap = !(ex.end() < s.start() || s.end() < ex.start());
                    // label overlap check: label extents must also be strictly separated
                    boolean labelOverlap = !(ex.labelEnd() < s.labelStart() || s.labelEnd() < ex.labelStart());
                    if (bodyOverlap || labelOverlap) {
                        fits = false;
                        break;
                    }
                }
                if (fits) { placed = r; break; }
            }
            if (placed < 0) {
                underlineRows.add(new ArrayList<>());
                placed = underlineRows.size() - 1;
            }
            underlineRows.get(placed).add(s);
        }

        var sb = new StringBuilder();
        sb.append(input).append('\n');

        for (var row : underlineRows) {
            // ── Step 2: determine render width ───────────────────────────────
            int width = input.length();
            for (Span s : row) {
                if (s.labelEnd() > width) width = s.labelEnd();
            }

            // ── Step 3: render underline row ─────────────────────────────────
            char[] uline = new char[width];
            Arrays.fill(uline, ' ');
            for (var s : row) {
                if (s.len() == 1) {
                    uline[s.start()] = VERT;
                } else {
                    // ─ body with ┬ at midpoint (both matches and markers)
                    for (int c = s.start(); c < s.end() && c < width; c++) {
                        uline[c] = HORIZ;
                    }
                    uline[s.mid()] = TEE;
                }
            }
            sb.append(new String(uline).stripTrailing()).append('\n');

            // ── Step 4: render single label line immediately below ────────────
            char[] lline = new char[width];
            Arrays.fill(lline, ' ');
            for (var s : row) {
                int lStart = s.labelStart();
                // expand if needed
                if (lStart + s.label().length() > lline.length) {
                    int newLen = lStart + s.label().length();
                    lline = Arrays.copyOf(lline, newLen);
                    Arrays.fill(lline, lline.length - (newLen - lline.length), lline.length, ' ');
                }
                for (int k = 0; k < s.label().length(); k++) {
                    lline[lStart + k] = s.label().charAt(k);
                }
            }
            sb.append(new String(lline).stripTrailing()).append('\n');
        }

        return sb.toString();
    }
}
