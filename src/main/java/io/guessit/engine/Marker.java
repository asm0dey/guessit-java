package io.guessit.engine;

/**
 * Named span over the input string, produced by {@link MarkerPhase}.
 *
 * <p>Three marker kinds are emitted by the default rules:
 * <ul>
 *   <li>{@code whole} — the entire input. Used as a fallback title scope.</li>
 *   <li>{@code path} — one per {@code /} or {@code \\}-separated segment.
 *       Used to scope rules to a single filepart (filename vs parent dir).</li>
 *   <li>{@code group} — one per balanced bracketed substring, e.g. {@code [...]}
 *       or {@code (...)}. Used by release-group, language, and similar rules.</li>
 * </ul>
 */
public record Marker(String name, int start, int end, String raw) {
    /** True if {@code pos} falls within this marker's half-open span. */
    public boolean contains(int pos) { return pos >= start && pos < end; }

    /** True if the {@code [s, e)} span lies entirely inside this marker. */
    public boolean covers(int s, int e) { return s >= start && e <= end; }

    public int length() { return end - start; }
}
