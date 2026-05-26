package io.guessit.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Replicates the subset of rebulk's {@code Rebulk.chain()} needed by
 * {@link io.guessit.rules.property.SeasonEpisodeExtractor}.
 */
public final class Chain {
    public enum Repeater { STAR, PLUS, QMARK }

    private final Pattern head;
    private final List<Step> tails = new ArrayList<>();

    public Chain(Pattern head) { this.head = head; }

    public Chain tail(Pattern tail, Repeater rep) {
        tails.add(new Step(tail, rep));
        return this;
    }

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

    private List<Matcher> buildTailMatchers(String input) {
        return tails.stream()
                .map(step -> {
                    var m = step.pattern().matcher(input).useAnchoringBounds(true);
                    m.useTransparentBounds(false);
                    return m;
                })
                .toList();
    }

    private record TailConsumption(int cursor, int tailCount, boolean ok) {}
    private record StepResult(int cursor, int taken) {}

    private TailConsumption consumeAllTails(String input, List<Matcher> tailMatchers, int hEnd,
                                            Map<String, List<String>> caps,
                                            Map<String, List<int[]>> spans,
                                            Matcher headMatcher) {
        int cursor = hEnd;
        int tailCount = 0;

        for (int si = 0; si < tails.size(); si++) {
            var step = tails.get(si);
            var sr = consumeStep(input, tailMatchers.get(si), step, cursor, caps, spans);

            cursor = sr.cursor();
            tailCount += sr.taken();

            if (step.rep() == Repeater.PLUS && sr.taken() == 0) {
                caps.clear();
                spans.clear();
                collectNamed(headMatcher, caps, spans);
                return new TailConsumption(hEnd, 0, !hasPlusStep());
            }
        }
        return new TailConsumption(cursor, tailCount, !(hasPlusStep() && tailCount == 0));
    }

    private StepResult consumeStep(String input, Matcher tm, Step step, int startCursor,
                                   Map<String, List<String>> caps,
                                   Map<String, List<int[]>> spans) {
        int cursor = startCursor;
        int taken = 0;

        int maxTakes = (step.rep() == Repeater.QMARK) ? 1 : Integer.MAX_VALUE;

        for (; taken < maxTakes; taken++) {
            tm.region(cursor, input.length());

            if (!tm.find() || (tm.start() > cursor && !isGapSeparators(input, cursor, tm.start()))) {
                break;
            }

            collectNamed(tm, caps, spans);
            cursor = tm.end();
        }

        return new StepResult(cursor, taken);
    }

    private boolean hasPlusStep() {
        return tails.stream().anyMatch(s -> s.rep() == Repeater.PLUS);
    }

    private static int nextFrom(int hStart, int hEnd, int cursor, Run run,
                                java.util.function.ToIntFunction<Run> effectiveEnd) {
        int adjusted = run != null ? effectiveEnd.applyAsInt(run) : -1;
        int advance = adjusted >= 0 ? adjusted : cursor;
        return Math.max(Math.max(hEnd, hStart + 1), advance);
    }

    private static void collectNamed(Matcher m, Map<String, List<String>> caps, Map<String, List<int[]>> spans) {
        namedGroups(m.pattern()).forEach(name -> {
            try {
                String v = m.group(name);
                if (v != null) {
                    caps.computeIfAbsent(name, _ -> new ArrayList<>()).add(v);
                    spans.computeIfAbsent(name, _ -> new ArrayList<>()).add(new int[]{m.start(name), m.end(name)});
                }
            } catch (IllegalArgumentException _) {
                // this group is not a part of this Matcher
            }
        });
    }

    private static boolean isGapSeparators(String input, int start, int end) {
        if (start >= end) return false;
        return input.substring(start, end).chars()
                .allMatch(c -> c == ' ' || c == '.' || c == '_' || c == '-' || c == '~');
    }

    private static final String GRP_NAME = "grpName";

    private static final Pattern NAMED_GROUP_DECL = Pattern.compile(
            fromAnywhere()
                    .of(literal("(?<"))
                    .then().namedCapture(capture(GRP_NAME,
                            exactly(1).letters().followedBy(zeroOrMore().alphanumeric())
                    ))
                    .followedBy(literal(">"))
                    .shake()
    );

    private static final ConcurrentMap<Pattern, List<String>> NAMED_GROUPS_CACHE = new ConcurrentHashMap<>();

    private static List<String> namedGroups(Pattern p) {
        return NAMED_GROUPS_CACHE.computeIfAbsent(p, Chain::scanNamedGroups);
    }

    private static List<String> scanNamedGroups(Pattern p) {
        return NAMED_GROUP_DECL.matcher(p.pattern())
                .results()
                .map(mr -> mr.group(GRP_NAME))
                .toList();
    }

    private record Step(Pattern pattern, Repeater rep) {}
}