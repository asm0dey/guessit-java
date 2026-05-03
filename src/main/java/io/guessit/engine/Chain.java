package io.guessit.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replicates the subset of rebulk's {@code Rebulk.chain()} needed by
 * {@link io.guessit.rules.property.SeasonEpisodeExtractor}.
 *
 * <p>A chain is a head pattern followed by an ordered series of tail patterns,
 * each annotated with a repetition: {@link Repeater#STAR star} (0+),
 * {@link Repeater#PLUS plus} (1+), or {@link Repeater#QMARK qmark} (0–1).
 * {@link #scan} walks the input, finding head matches and greedily consuming
 * contiguous tail matches (allowing a small set of separator characters
 * between them).
 *
 * <p>Captured named groups from both head and tail are accumulated into the
 * returned {@link Run}, preserving order so callers can pair, say, a series
 * of episode numbers with their literal substrings.
 */
public final class Chain {
    /** Repetition flag for each tail step. */
    public enum Repeater { STAR, PLUS, QMARK }

    private final Pattern head;
    private final List<Step> tails = new ArrayList<>();

    public Chain(Pattern head) { this.head = head; }

    public Chain tail(Pattern tail, Repeater rep) { tails.add(new Step(tail, rep)); return this; }

    /** A single chain run: one head match followed by zero or more tail matches contiguous to head.end(). */
    public static final class Run {
        private final int start;
        private final int end;
        private final Map<String, List<String>> captures;
        private final Map<String, List<int[]>> spans;
        Run(int start, int end, Map<String, List<String>> captures, Map<String, List<int[]>> spans) {
            this.start = start; this.end = end; this.captures = captures; this.spans = spans;
        }
        public int start() { return start; }
        public int end() { return end; }
        public List<String> captures(String name) { return captures.getOrDefault(name, List.of()); }
        public List<int[]> spans(String name) { return spans.getOrDefault(name, List.of()); }
    }

    public List<Run> scan(String input) {
        var runs = new ArrayList<Run>();
        var headMatcher = head.matcher(input);
        int from = 0;
        while (headMatcher.find(from)) {
            int hStart = headMatcher.start();
            int hEnd = headMatcher.end();
            var caps = new LinkedHashMap<String, List<String>>();
            var spans = new LinkedHashMap<String, List<int[]>>();
            collectNamed(headMatcher, caps, spans);

            int cursor = hEnd;
            int tailCount = 0;
            for (var step : tails) {
                int taken = 0;
                while (true) {
                    var tm = step.pattern.matcher(input).region(cursor, input.length()).useAnchoringBounds(true);
                    tm.useTransparentBounds(false);
                    if (!tm.find()) break;
                    // Tail must be contiguous to the cursor, optionally separated
                    // by a small set of innocuous chars. This is what makes a
                    // "chain" — without it, any later occurrence of the tail
                    // pattern in the input would match.
                    if (tm.start() > cursor) {
                        var gap = input.substring(cursor, tm.start());
                        if (!gap.isEmpty() && !gap.matches("[ ._\\-~]+")) break;
                    }
                    collectNamed(tm, caps, spans);
                    cursor = tm.end();
                    taken++;
                    tailCount++;
                    if (step.rep == Repeater.QMARK) break;
                }
                if (step.rep == Repeater.PLUS && taken == 0) {
                    // PLUS step matched zero times — the chain run is invalid.
                    // Roll back to head-only state so the outer loop tries the
                    // next head occurrence cleanly.
                    cursor = hEnd;
                    caps.clear();
                    spans.clear();
                    collectNamed(headMatcher, caps, spans);
                    tailCount = 0;
                    break;
                }
            }

            boolean ok = true;
            for (var step : tails) {
                if (step.rep == Repeater.PLUS && tailCount == 0) { ok = false; break; }
            }
            if (ok) runs.add(new Run(hStart, cursor, caps, spans));
            from = Math.max(hEnd, hStart + 1);
        }
        return runs;
    }

    private static void collectNamed(Matcher m, Map<String, List<String>> caps, Map<String, List<int[]>> spans) {
        for (var name : namedGroups(m.pattern())) {
            String v;
            try { v = m.group(name); } catch (IllegalArgumentException _) { continue; }
            if (v == null) continue;
            caps.computeIfAbsent(name, _ -> new ArrayList<>()).add(v);
            spans.computeIfAbsent(name, _ -> new ArrayList<>()).add(new int[]{m.start(name), m.end(name)});
        }
    }

    /**
     * Java's {@link Pattern} exposes no API to enumerate declared named groups,
     * so parse the pattern source for {@code (?<name>...)} occurrences directly.
     */
    private static List<String> namedGroups(Pattern p) {
        var out = new ArrayList<String>();
        var src = p.pattern();
        var nm = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").matcher(src);
        while (nm.find()) out.add(nm.group(1));
        return out;
    }

    private record Step(Pattern pattern, Repeater rep) {}
}
