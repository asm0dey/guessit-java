package io.guessit.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders an input string with per-underline-row underline + staggered label
 * lines for every span, using box-drawing characters.
 *
 * <p>Layout rules:
 * <ul>
 *   <li>Match span underline: {@code ─} (U+2500) body with {@code ┬}
 *       (U+252C) at the midpoint.</li>
 *   <li>Marker span underline: {@code ┌} corner, {@code ─} short horizontals,
 *       gap in the middle for length &ge; 5, {@code ┐} corner. Length 1: {@code │};
 *       length 2: {@code ┌┐}; length 3: {@code ┌─┐}; length 4: {@code ┌──┐}.</li>
 *   <li>Single-char span (both kinds): {@code │} (U+2502) at that column.</li>
 *   <li>Vertical connector descending from midpoint to label depth:
 *       {@code │} on every intermediate row.</li>
 *   <li>Spans share an underline row when there is at least one column gap
 *       between them; touching ({@code end_i == start_j}) or overlapping
 *       forces a new underline row.</li>
 *   <li>Per-underline-row greedy label depth: lowest depth where the label's
 *       horizontal extent does not collide with any already-placed label at
 *       that depth for the same underline row.</li>
 *   <li>No leading indent — output starts at column 0.</li>
 *   <li>Trailing whitespace stripped per line.</li>
 * </ul>
 */
public final class SpanRenderer {

    // Box-drawing constants
    private static final char HORIZ        = '─'; // U+2500
    private static final char TEE          = '┬'; // U+252C
    private static final char VERT         = '│'; // U+2502
    private static final char CORNER_LEFT  = '┌'; // U+250C
    private static final char CORNER_RIGHT = '┐'; // U+2510

    private SpanRenderer() {}

    public static String render(String input, List<Match> matches, List<Marker> markers) {
        record Span(int start, int end, String label, boolean isMarker) {
            int mid() { return start + (end - start) / 2; }
            int len() { return end - start; }
        }

        var spans = new ArrayList<Span>();
        for (var m : matches) {
            if (m.isPrivate()) continue;
            spans.add(new Span(m.start(), m.end(), m.name().name().toLowerCase(Locale.ROOT), false));
        }
        for (var mk : markers) {
            spans.add(new Span(mk.start(), mk.end(), mk.name(), true));
        }

        if (spans.isEmpty()) {
            return input + "\n";
        }

        spans.sort(Comparator.<Span>comparingInt(Span::start).thenComparingInt(Span::end));

        // ── Step 1: assign each span to an underline row ─────────────────────
        // Two spans share a row iff there is at least one column gap between
        // them (existing.end < s.start, i.e. strictly less than).
        var underlineRows = new ArrayList<List<Span>>();
        for (var s : spans) {
            int placed = -1;
            for (int r = 0; r < underlineRows.size(); r++) {
                boolean fits = true;
                for (var ex : underlineRows.get(r)) {
                    // touching (ex.end == s.start) or overlapping → new row
                    if (!(ex.end() < s.start() || s.end() < ex.start())) {
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
            // ── Step 2: compute label depths for this underline row ───────────
            // labelDepth[i] = depth assigned to row.get(i)
            int[] labelDepth = new int[row.size()];
            // For each depth, track [labelStart, labelEnd) intervals already placed
            var depthIntervals = new ArrayList<List<int[]>>();

            for (int i = 0; i < row.size(); i++) {
                var s = row.get(i);
                int halfLabel = s.label().length() / 2;
                int lStart = Math.max(0, s.mid() - halfLabel);
                int lEnd   = lStart + s.label().length();

                int d = 0;
                outer:
                for (;; d++) {
                    if (d >= depthIntervals.size()) {
                        // No intervals at this depth yet → fits
                        break;
                    }
                    for (var iv : depthIntervals.get(d)) {
                        // overlap: lStart < iv[1]+1 && iv[0] < lEnd+1
                        if (lStart < iv[1] + 1 && iv[0] < lEnd + 1) {
                            continue outer; // try next depth
                        }
                    }
                    break; // fits at depth d
                }
                labelDepth[i] = d;
                while (depthIntervals.size() <= d) depthIntervals.add(new ArrayList<>());
                depthIntervals.get(d).add(new int[]{lStart, lEnd});
            }

            int maxDepth = 0;
            for (int d : labelDepth) maxDepth = Math.max(maxDepth, d);

            // ── Step 3: render underline row ─────────────────────────────────
            // Determine the width needed (at least input.length())
            int width = input.length();
            // Also ensure label extents fit
            for (int i = 0; i < row.size(); i++) {
                var s = row.get(i);
                int halfLabel = s.label().length() / 2;
                int lStart = Math.max(0, s.mid() - halfLabel);
                int lEnd = lStart + s.label().length();
                if (lEnd > width) width = lEnd;
            }

            char[] uline = new char[width];
            Arrays.fill(uline, ' ');
            for (var s : row) {
                if (s.len() == 1) {
                    uline[s.start()] = VERT;
                } else if (!s.isMarker()) {
                    // Match underline: ─ body with ┬ at midpoint
                    for (int c = s.start(); c < s.end() && c < width; c++) {
                        uline[c] = HORIZ;
                    }
                    uline[s.mid()] = TEE;
                } else {
                    // Marker underline: ┌─ … ─┐ corners with gap in the middle for len ≥ 5
                    int len = s.len();
                    if (len == 2) {
                        uline[s.start()]     = CORNER_LEFT;
                        uline[s.start() + 1] = CORNER_RIGHT;
                    } else if (len == 3) {
                        uline[s.start()]     = CORNER_LEFT;
                        uline[s.start() + 1] = HORIZ;
                        uline[s.start() + 2] = CORNER_RIGHT;
                    } else if (len == 4) {
                        uline[s.start()]     = CORNER_LEFT;
                        uline[s.start() + 1] = HORIZ;
                        uline[s.start() + 2] = HORIZ;
                        uline[s.start() + 3] = CORNER_RIGHT;
                    } else {
                        // len ≥ 5: ┌─ + spaces × (len − 4) + ─┐
                        uline[s.start()]         = CORNER_LEFT;
                        uline[s.start() + 1]     = HORIZ;
                        // interior gap: positions start+2 .. end-3 stay as space (already filled)
                        uline[s.end() - 2]       = HORIZ;
                        uline[s.end() - 1]       = CORNER_RIGHT;
                    }
                }
            }
            sb.append(new String(uline).stripTrailing()).append('\n');

            // ── Step 4: render connector + label lines ────────────────────────
            // For depths 0..maxDepth, produce one line per depth.
            // On each line d: for every span s at labelDepth d → write label;
            //                  for every span s at labelDepth > d → write │ at mid.
            for (int d = 0; d <= maxDepth; d++) {
                char[] line = new char[width];
                Arrays.fill(line, ' ');
                for (int i = 0; i < row.size(); i++) {
                    var s = row.get(i);
                    int mid = s.mid();
                    if (labelDepth[i] > d) {
                        // connector
                        if (mid < width) line[mid] = VERT;
                    } else if (labelDepth[i] == d) {
                        // write label centered on mid
                        int halfLabel = s.label().length() / 2;
                        int lStart = Math.max(0, mid - halfLabel);
                        while (lStart + s.label().length() > line.length) {
                            // expand line array
                            line = Arrays.copyOf(line, line.length + s.label().length());
                            Arrays.fill(line, line.length - s.label().length(), line.length, ' ');
                        }
                        for (int k = 0; k < s.label().length(); k++) {
                            line[lStart + k] = s.label().charAt(k);
                        }
                    }
                    // else: label was placed at a lower depth, nothing here
                }
                sb.append(new String(line).stripTrailing()).append('\n');
            }
        }

        return sb.toString();
    }
}
