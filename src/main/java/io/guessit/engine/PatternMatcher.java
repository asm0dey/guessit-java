package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mirkoddd.sift.core.SiftPatterns.literal;

/**
 * Match-producing helpers used by most {@link Extractor}s.
 *
 * <p>Two scanners:
 * <ul>
 *   <li>{@link #regex} — runs a single {@link Pattern} over the input, with
 *       per-match value extraction, value formatting, and validation.</li>
 *   <li>{@link #string} — scans for a fixed set of literal needles, with
 *       case and whole-word handling.</li>
 * </ul>
 *
 * <p>Both return ordered {@link Match} lists ready for {@link MatchSet#add}.
 */
public final class PatternMatcher {

    private PatternMatcher() {}

    public static List<Match> regex(String input, Pattern pattern, MatchName name, RegexOpts opts) {
        return regex(input, pattern, name, opts, Trace.NOOP);
    }

    public static List<Match> string(String input, Set<String> needles, MatchName name, StringOpts opts) {
        return string(input, needles, name, opts, Trace.NOOP);
    }

    public static List<Match> regex(String input, Pattern pattern, MatchName name, RegexOpts opts, Trace trace) {
        trace.subStep("Trying regex " + pattern.pattern());
        var out = new ArrayList<Match>();
        var m = pattern.matcher(input);
        boolean hasValueGroup = HAS_VALUE_GROUP.computeIfAbsent(pattern, PatternMatcher::detectValueGroup);

        while (m.find()) {
            String raw = m.group();
            int start = m.start();
            int end = m.end();

            String valueText = hasValueGroup ? m.group("value") : raw;
            Object extracted = opts.valueExtractor().apply(valueText);
            Object formatted = opts.valueFormatter().apply(extracted);

            var match = new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate());

            if (opts.validator().test(match)) {
                out.add(match);
                traceDecision(trace, raw, start, end, "accepted");
            } else {
                traceDecision(trace, raw, start, end, "rejected (validator)");
            }
        }
        return out;
    }

    public static List<Match> string(String input, Set<String> needles, MatchName name, StringOpts opts, Trace trace) {
        trace.subStep("Trying needles: " + summariseNeedles(needles));
        var hay = opts.caseSensitive() ? input : input.toLowerCase(java.util.Locale.ROOT);

        return needles.stream()
                .flatMap(raw -> {
                    var n = opts.caseSensitive() ? raw : raw.toLowerCase(java.util.Locale.ROOT);
                    return scanNeedle(input, hay, raw, n, name, opts, trace).stream();
                })
                .sorted(Comparator.comparingInt(Match::start))
                .toList();
    }

    private static List<Match> scanNeedle(String input, String hay, String raw, String n,
                                          MatchName name, StringOpts opts, Trace trace) {
        var matches = new ArrayList<Match>();
        int from = 0;
        int idx;

        while ((idx = hay.indexOf(n, from)) >= 0) {
            int end = idx + n.length();
            boolean wordOk = !opts.wholeWord() || isWordBoundary(hay, idx, end);

            if (wordOk) {
                var match = new Match(name, raw, idx, end, input.substring(idx, end),
                        opts.priority(), opts.tags(), opts.isPrivate());

                if (opts.validator().test(match)) {
                    matches.add(match);
                    traceDecision(trace, raw, idx, end, "accepted");
                } else {
                    traceDecision(trace, raw, idx, end, "rejected (validator)");
                }
            } else {
                traceDecision(trace, raw, idx, end, "rejected (word boundary)");
            }

            from = idx + 1;
        }
        return matches;
    }

    private static void traceDecision(Trace trace, String raw, int start, int end, String outcome) {
        trace.subStep("Considered '" + raw + "' at " + start + "-" + end + " — " + outcome);
    }

    private static String summariseNeedles(Set<String> needles) {
        String sortedNeedles = needles.stream()
                .sorted()
                .limit(6)
                .collect(Collectors.joining(", "));

        return needles.size() <= 6
                ? sortedNeedles
                : sortedNeedles + ", … (" + needles.size() + " total)";
    }

    private static final ConcurrentMap<Pattern, Boolean> HAS_VALUE_GROUP = new ConcurrentHashMap<>();
    private static final Pattern VALUE_GROUP_DECL = Pattern.compile(
            literal("(?<value>").shake()
    );

    private static boolean detectValueGroup(Pattern p) {
        return VALUE_GROUP_DECL.matcher(p.pattern()).find();
    }

    private static boolean isWordBoundary(String s, int start, int end) {
        if (start > 0 && Character.isLetterOrDigit(s.charAt(start - 1))) return false;
        return end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
    }
}