package io.guessit.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders an input string with underline + label rows for every span.
 *
 * <p>Layout:
 * <pre>
 *   XxX.2020.mkv
 *       ---- ---
 *        |    |
 *       year container
 * </pre>
 *
 * <p>Spans are stacked across multiple label rows when their midpoints are
 * too close to fit labels on a single row without overlap. Connector {@code |}
 * characters are repeated above the label so each label is visually anchored
 * to the underline at the span midpoint.
 *
 * <p>All output lines are indented by two spaces (sub-step indent level).
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

        // Underline row
        var underline = new char[width];
        Arrays.fill(underline, ' ');
        for (var s : spans) {
            for (int i = s.start(); i < s.end() && i < width; i++) underline[i] = '-';
        }

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
        sb.append("  ").append(new String(underline).replaceAll("\\s+$", "")).append('\n');

        for (int r = 0; r < rows.size(); r++) {
            var connectors = new char[width];
            Arrays.fill(connectors, ' ');
            for (int rr = r; rr < rows.size(); rr++) {
                for (var s : rows.get(rr)) {
                    if (s.mid() < width) connectors[s.mid()] = '|';
                }
            }
            sb.append("  ").append(new String(connectors).replaceAll("\\s+$", "")).append('\n');

            var dynamic = new StringBuilder();
            while (dynamic.length() < width) dynamic.append(' ');
            for (var s : rows.get(r)) {
                int halfLabel = s.label().length() / 2;
                int start = Math.max(0, s.mid() - halfLabel);
                while (dynamic.length() < start + s.label().length()) dynamic.append(' ');
                for (int i = 0; i < s.label().length(); i++) {
                    dynamic.setCharAt(start + i, s.label().charAt(i));
                }
            }
            sb.append("  ").append(dynamic.toString().replaceAll("\\s+$", "")).append('\n');
        }
        return sb.toString();
    }
}
