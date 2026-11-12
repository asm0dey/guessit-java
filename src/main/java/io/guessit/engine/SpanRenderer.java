package io.guessit.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

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

    private static final char HORIZ = '─'; // U+2500
    private static final char TEE   = '┬'; // U+252C
    private static final char VERT  = '│'; // U+2502

    private SpanRenderer() {}

    public static String render(String input, List<Match> matches, List<Marker> markers) {
        var spans = buildSpans(matches, markers);
        if (spans.isEmpty()) {
            return input + "\n";
        }

        var underlineRows = assignRows(spans);
        var sb = new StringBuilder().append(input).append('\n');

        for (var row : underlineRows) {
            renderRow(row, input.length(), sb);
        }

        return sb.toString();
    }

    private static List<Span> buildSpans(List<Match> matches, List<Marker> markers) {
        var matchSpans = matches.stream()
                .filter(m -> !m.isPrivate())
                .map(m -> new Span(m.start(), m.end(), m.name().name().toLowerCase(Locale.ROOT)));

        var markerSpans = markers.stream()
                .map(mk -> new Span(mk.start(), mk.end(), mk.name()));

        return Stream.concat(matchSpans, markerSpans)
                .sorted(Comparator.comparingInt(Span::start).thenComparingInt(Span::end))
                .toList();
    }

    private static List<List<Span>> assignRows(List<Span> spans) {
        var rows = new ArrayList<List<Span>>();

        for (var span : spans) {
            rows.stream()
                    .filter(row -> row.stream().noneMatch(existing -> existing.overlaps(span)))
                    .findFirst()
                    .ifPresentOrElse(
                            row -> row.add(span),
                            () -> {
                                var newRow = new ArrayList<Span>();
                                newRow.add(span);
                                rows.add(newRow);
                            }
                    );
        }
        return rows;
    }

    private static void renderRow(List<Span> row, int inputLength, StringBuilder sb) {
        int width = Math.max(inputLength, row.stream().mapToInt(Span::labelEnd).max().orElse(0));

        char[] underlineChars = new char[width];
        Arrays.fill(underlineChars, ' ');

        for (var s : row) {
            if (s.len() == 1) {
                underlineChars[s.start()] = VERT;
            } else {
                for (int c = s.start(); c < s.end() && c < width; c++) {
                    underlineChars[c] = HORIZ;
                }
                underlineChars[s.mid()] = TEE;
            }
        }
        sb.append(new String(underlineChars).stripTrailing()).append('\n');

        char[] labelChars = new char[width];
        Arrays.fill(labelChars, ' ');

        for (var s : row) {
            int lStart = s.labelStart();
            for (int k = 0; k < s.label().length(); k++) {
                labelChars[lStart + k] = s.label().charAt(k);
            }
        }
        sb.append(new String(labelChars).stripTrailing()).append('\n');
    }

    private record Span(int start, int end, String label) {
        int mid() { return start + (end - start) / 2; }
        int len() { return end - start; }
        int labelStart() { return Math.max(0, mid() - (label.length() / 2)); }
        int labelEnd() { return labelStart() + label.length(); }

        boolean overlaps(Span other) {
            boolean bodyOverlap = !(other.end() < this.start() || this.end() < other.start());
            boolean labelOverlap = !(other.labelEnd() < this.labelStart() || this.labelEnd() < other.labelStart());
            return bodyOverlap || labelOverlap;
        }
    }
}