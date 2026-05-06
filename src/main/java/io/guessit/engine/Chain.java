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
        var tailMatchers = buildTailMatchers(input);
        int from = 0;
        while (headMatcher.find(from)) {
            int hStart = headMatcher.start();
            int hEnd = headMatcher.end();
            var caps = new LinkedHashMap<String, List<String>>();
            var spans = new LinkedHashMap<String, List<int[]>>();
            collectNamed(headMatcher, caps, spans);

            var consumed = consumeAllTails(input, tailMatchers, hEnd, caps, spans, headMatcher);
            Run run = consumed.ok() ? new Run(hStart, consumed.cursor(), caps, spans) : null;
            if (run != null) runs.add(run);
            from = nextFrom(hStart, hEnd, consumed.cursor(), run, effectiveEnd);
        }
        return runs;
    }

    private Matcher[] buildTailMatchers(String input) {
        var arr = new Matcher[tails.size()];
        for (int i = 0; i < tails.size(); i++) {
            arr[i] = tails.get(i).pattern.matcher(input).useAnchoringBounds(true);
            arr[i].useTransparentBounds(false);
        }
        return arr;
    }

    private record TailConsumption(int cursor, int tailCount, boolean ok) {}

    private record StepResult(int cursor, int taken) {}

    /**
     * Walks the configured tail steps starting at {@code hEnd}. On a PLUS
     * step that captures nothing, resets the run to head-only.
     */
    private TailConsumption consumeAllTails(String input, Matcher[] tailMatchers, int hEnd,
                                            Map<String, List<String>> caps,
                                            Map<String, List<int[]>> spans,
                                            Matcher headMatcher) {
        int cursor = hEnd;
        int tailCount = 0;
        for (int si = 0; si < tails.size(); si++) {
            var step = tails.get(si);
            var sr = consumeStep(input, tailMatchers[si], step, cursor, caps, spans);
            cursor = sr.cursor();
            tailCount += sr.taken();
            if (step.rep == Repeater.PLUS && sr.taken() == 0) {
                caps.clear();
                spans.clear();
                collectNamed(headMatcher, caps, spans);
                return new TailConsumption(hEnd, 0, !hasPlusStep());
            }
        }
        return new TailConsumption(cursor, tailCount, !(hasPlusStep() && tailCount == 0));
    }

    /**
     * Greedily consumes one tail step, requiring the tail be contiguous to
     * {@code startCursor} (optionally separated by gap chars). QMARK caps at one.
     */
    private StepResult consumeStep(String input, Matcher tm, Step step, int startCursor,
                                   Map<String, List<String>> caps,
                                   Map<String, List<int[]>> spans) {
        int cursor = startCursor;
        int taken = 0;
        while (true) {
            tm.region(cursor, input.length());
            if (!tm.find()) break;
            if (tm.start() > cursor && !isGapSeparators(input, cursor, tm.start())) break;
            collectNamed(tm, caps, spans);
            cursor = tm.end();
            taken++;
            if (step.rep == Repeater.QMARK) break;
        }
        return new StepResult(cursor, taken);
    }

    private boolean hasPlusStep() {
        for (var s : tails) if (s.rep == Repeater.PLUS) return true;
        return false;
    }

    /**
     * Advance past the consumed run (head + tails) so the next head search
     * does not re-capture a tail digit as its own head, unless the validator
     * says we should resume earlier.
     */
    private static int nextFrom(int hStart, int hEnd, int cursor, Run run,
                                java.util.function.ToIntFunction<Run> effectiveEnd) {
        int adjusted = run != null ? effectiveEnd.applyAsInt(run) : -1;
        int advance = adjusted >= 0 ? adjusted : cursor;
        return Math.max(Math.max(hEnd, hStart + 1), advance);
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
