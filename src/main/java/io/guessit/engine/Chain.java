package io.guessit.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    public List<Run> scan(String input) { return scan(input, _ -> -1); }

    /**
     * Scan with a per-run "effective end" callback. The callback receives the
     * full Run (head + greedy tails) and returns the position at which the
     * next head search should resume — typically after the caller-validated
     * portion of the run. Returning a negative value means "trust the run as
     * captured" (advance past the full cursor). When the callback returns
     * a value strictly less than the run's full end, {@code from} is set to
     * that adjusted position so subsequent runs can capture the tail digits
     * the validator rejected.
     */
    public List<Run> scan(String input, java.util.function.ToIntFunction<Run> effectiveEnd) {
        var runs = new ArrayList<Run>();
        var headMatcher = head.matcher(input);
        // Allocate one Matcher per tail step, reused across all head iterations.
        var tailMatchers = new Matcher[tails.size()];
        for (int i = 0; i < tails.size(); i++) {
            tailMatchers[i] = tails.get(i).pattern.matcher(input)
                .useAnchoringBounds(true);
            tailMatchers[i].useTransparentBounds(false);
        }
        int from = 0;
        while (headMatcher.find(from)) {
            int hStart = headMatcher.start();
            int hEnd = headMatcher.end();
            var caps = new LinkedHashMap<String, List<String>>();
            var spans = new LinkedHashMap<String, List<int[]>>();
            collectNamed(headMatcher, caps, spans);

            int cursor = hEnd;
            int tailCount = 0;
            for (int si = 0; si < tails.size(); si++) {
                var step = tails.get(si);
                var tm = tailMatchers[si];
                int taken = 0;
                while (true) {
                    tm.region(cursor, input.length());
                    if (!tm.find()) break;
                    // Tail must be contiguous to the cursor, optionally separated
                    // by a small set of innocuous chars. This is what makes a
                    // "chain" — without it, any later occurrence of the tail
                    // pattern in the input would match.
                    if (tm.start() > cursor) {
                        if (!isGapSeparators(input, cursor, tm.start())) break;
                    }
                    collectNamed(tm, caps, spans);
                    cursor = tm.end();
                    taken++;
                    tailCount++;
                    if (step.rep == Repeater.QMARK) break;
                }
                if (step.rep == Repeater.PLUS && taken == 0) {
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
            Run run = ok ? new Run(hStart, cursor, caps, spans) : null;
            if (run != null) runs.add(run);
            int adjusted = run != null ? effectiveEnd.applyAsInt(run) : -1;
            int advance = adjusted >= 0 ? adjusted : cursor;
            // Advance past the entire consumed run (head + tails) so the next
            // head search does not re-capture a tail digit as its own head,
            // unless the validator says we should resume earlier.
            from = Math.max(Math.max(hEnd, hStart + 1), advance);
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
     * Char-set check equivalent to regex {@code [ ._\-~]+} on the
     * {@code [start, end)} slice of {@code input}. Avoids regex overhead in the
     * tight chain inner loop.
     */
    private static boolean isGapSeparators(String input, int start, int end) {
        if (start >= end) return false;
        for (int i = start; i < end; i++) {
            char c = input.charAt(i);
            if (c != ' ' && c != '.' && c != '_' && c != '-' && c != '~') return false;
        }
        return true;
    }

    private static final Pattern NAMED_GROUP_DECL = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>");
    private static final ConcurrentMap<Pattern, List<String>> NAMED_GROUPS_CACHE = new ConcurrentHashMap<>();

    /**
     * Java's {@link Pattern} exposes no API to enumerate declared named groups,
     * so parse the pattern source for {@code (?<name>...)} occurrences directly.
     * Cached per {@link Pattern} since chain patterns are reused across parses.
     */
    private static List<String> namedGroups(Pattern p) {
        return NAMED_GROUPS_CACHE.computeIfAbsent(p, Chain::scanNamedGroups);
    }

    private static List<String> scanNamedGroups(Pattern p) {
        var out = new ArrayList<String>();
        var nm = NAMED_GROUP_DECL.matcher(p.pattern());
        while (nm.find()) out.add(nm.group(1));
        return List.copyOf(out);
    }

    private record Step(Pattern pattern, Repeater rep) {}
}
