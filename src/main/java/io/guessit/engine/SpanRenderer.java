package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders an input string with per-row underline + label lines for every span.
 *
 * <p>Layout:
 * <pre>
 *   XxX.2020.mkv
 *       ----
 *       year
 *            ---
 *            container
 * </pre>
 *
 * <p>Each row is fully self-contained: an underline row showing only that
 * row's spans, immediately followed by a label row. No connector {@code |}
 * lines link rows together.
 *
 * <p>Underline character: {@code -} for multi-char spans (length &ge; 2),
 * {@code |} for single-char spans (length == 1).
 *
 * <p>Spans are assigned greedily to rows: a span goes to the lowest row
 * that has no horizontal label-area overlap with already-placed spans in
 * that row. Overlapping spans (same start/end) each get their own row.
 *
 * <p>All output lines are indented by two spaces and have trailing whitespace
 * stripped.
 */
public final class SpanRenderer {

    private SpanRenderer() {}

    public static String render(String input, List<Match> matches, List<Marker> markers) {
        record Span(int start, int end, String label) {
            int mid() { return start + (end - start) / 2; }
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
            return "  " + input + "\n";
        }
        spans.sort(Comparator.<Span>comparingInt(Span::start).thenComparingInt(Span::end));

        int width = input.length();

        // Greedy label-row assignment.
        var rows = new ArrayList<List<Span>>();
        for (var s : spans) {
            int halfLabel = s.label().length() / 2;
            int labelStart = Math.max(0, s.mid() - halfLabel);
            int labelEnd = labelStart + s.label().length();
            int placedRow = -1;
            for (int r = 0; r < rows.size(); r++) {
                boolean fits = true;
                for (var existing : rows.get(r)) {
                    // 1. Underline gap: must have at least one column between underlines.
                    boolean underlineTouchesOrOverlaps = !(existing.end() < s.start() || s.end() < existing.start());
                    if (underlineTouchesOrOverlaps) { fits = false; break; }

                    // 2. Label gap: existing label-area no-overlap check.
                    int eHalf = existing.label().length() / 2;
                    int eStart = Math.max(0, existing.mid() - eHalf);
                    int eEnd = eStart + existing.label().length();
                    if (labelStart < eEnd + 1 && eStart < labelEnd + 1) { fits = false; break; }
                }
                if (fits) { placedRow = r; break; }
            }
            if (placedRow < 0) { rows.add(new ArrayList<>()); placedRow = rows.size() - 1; }
            rows.get(placedRow).add(s);
        }

        var sb = new StringBuilder();
        sb.append("  ").append(input).append('\n');

        for (var row : rows) {
            // Underline line: '-' for spans with length >= 2, '|' for length == 1.
            var underline = new char[width];
            java.util.Arrays.fill(underline, ' ');
            for (var s : row) {
                char ch = (s.end() - s.start() == 1) ? '|' : '-';
                for (int i = s.start(); i < s.end() && i < width; i++) underline[i] = ch;
            }
            sb.append("  ").append(new String(underline).stripTrailing()).append('\n');

            // Label line: each label centered on the span's midpoint.
            var labelLine = new StringBuilder();
            while (labelLine.length() < width) labelLine.append(' ');
            for (var s : row) {
                int halfLabel = s.label().length() / 2;
                int start = Math.max(0, s.mid() - halfLabel);
                while (labelLine.length() < start + s.label().length()) labelLine.append(' ');
                for (int i = 0; i < s.label().length(); i++) {
                    labelLine.setCharAt(start + i, s.label().charAt(i));
                }
            }
            sb.append("  ").append(labelLine.toString().stripTrailing()).append('\n');
        }
        return sb.toString();
    }
}
