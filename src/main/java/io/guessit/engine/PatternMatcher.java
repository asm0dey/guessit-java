package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

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

    /* No-trace overloads delegate to the trace-aware versions with NOOP. */

    /**
     * Scans every non-overlapping match of {@code pattern} in {@code input}.
     *
     * <p>The match value is taken from the named group {@code "value"} when
     * present, otherwise from the full match. {@code RegexOpts.valueExtractor}
     * converts the raw substring to a typed value (e.g. {@code Integer::valueOf});
     * {@code valueFormatter} optionally remaps it (e.g. canonicalisation).
     * The {@code validator} sees the constructed match and may veto it.
     */
    public static List<Match> regex(String input, Pattern pattern, MatchName name, RegexOpts opts) {
        return regex(input, pattern, name, opts, Trace.NOOP);
    }

    /**
     * Scans for every occurrence of every needle in {@code needles}.
     *
     * <p>Lower-cases both haystack and needle when {@code caseSensitive} is
     * false. Spans are reported against the original input so {@link Match#raw}
     * preserves the original casing. When {@code wholeWord} is true (default),
     * matches require non-alphanumeric neighbours on both sides.
     *
     * <p>Output is sorted by start position; ordering across duplicate-start
     * needles is unspecified.
     */
    @SuppressWarnings("JavadocReference")
    public static List<Match> string(String input, Set<String> needles, MatchName name, StringOpts opts) {
        return string(input, needles, name, opts, Trace.NOOP);
    }

    /* Trace-aware overloads emit subStep events for each try / decision. */

    public static List<Match> regex(String input, Pattern pattern, MatchName name, RegexOpts opts, Trace trace) {
        trace.subStep("Trying regex " + pattern.pattern());
        var out = new ArrayList<Match>();
        var m = pattern.matcher(input);
        boolean hasValueGroup = HAS_VALUE_GROUP.computeIfAbsent(pattern, PatternMatcher::detectValueGroup);
        while (m.find()) {
            String raw = m.group();
            int start = m.start();
            int end = m.end();
            // Prefer the named "value" group when defined so patterns can match
            // surrounding context (separators, anchors) without bleeding it into
            // the property value.
            String valueText = hasValueGroup ? m.group("value") : raw;
            Object extracted = opts.valueExtractor().apply(valueText);
            Object formatted = opts.valueFormatter().apply(extracted);
            var match = new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate());
            if (opts.validator().test(match)) {
                out.add(match);
                trace.subStep("Considered '" + raw + "' at " + start + "-" + end + " — accepted");
            } else {
                trace.subStep("Considered '" + raw + "' at " + start + "-" + end + " — rejected (validator)");
            }
        }
        return out;
    }

    public static List<Match> string(String input, Set<String> needles, MatchName name, StringOpts opts, Trace trace) {
        trace.subStep("Trying needles: " + summariseNeedles(needles));
        var out = new ArrayList<Match>();
        var hay = opts.caseSensitive() ? input : input.toLowerCase(java.util.Locale.ROOT);
        for (var raw : needles) {
            var n = opts.caseSensitive() ? raw : raw.toLowerCase(java.util.Locale.ROOT);
            scanNeedle(input, hay, raw, n, name, opts, out, trace);
        }
        out.sort(Comparator.comparingInt(Match::start));
        return out;
    }

    private static void scanNeedle(String input, String hay, String raw, String n,
                                   MatchName name, StringOpts opts, List<Match> out, Trace trace) {
        int from = 0;
        while (true) {
            int idx = hay.indexOf(n, from);
            if (idx < 0) break;
            int end = idx + n.length();
            boolean wordOk = !opts.wholeWord() || isWordBoundary(hay, idx, end);
            if (wordOk) {
                var match = new Match(name, raw, idx, end, input.substring(idx, end),
                    opts.priority(), opts.tags(), opts.isPrivate());
                if (opts.validator().test(match)) {
                    out.add(match);
                    trace.subStep("Considered '" + raw + "' at " + idx + "-" + end + " — accepted");
                } else {
                    trace.subStep("Considered '" + raw + "' at " + idx + "-" + end + " — rejected (validator)");
                }
            } else {
                trace.subStep("Considered '" + raw + "' at " + idx + "-" + end + " — rejected (word boundary)");
            }
            // Step by one rather than n.length() so overlapping needles
            // (e.g. "aa" in "aaa") still both report.
            from = idx + 1;
        }
    }

    private static String summariseNeedles(Set<String> needles) {
        if (needles.size() <= 6) {
            return String.join(", ", new java.util.TreeSet<>(needles));
        }
        var sorted = new java.util.TreeSet<>(needles);
        var first6 = sorted.stream().limit(6).toList();
        return String.join(", ", first6) + ", … (" + needles.size() + " total)";
    }

    private static final ConcurrentMap<Pattern, Boolean> HAS_VALUE_GROUP = new ConcurrentHashMap<>();
    private static final Pattern VALUE_GROUP_DECL = Pattern.compile("\\(\\?<value>");

    /**
     * Java exposes no public "has named group" query. Parse the pattern source
     * once for {@code (?<value>}; cache the answer per {@link Pattern}. Cheaper
     * than catching {@link IllegalArgumentException} per match.
     */
    private static boolean detectValueGroup(Pattern p) {
        return VALUE_GROUP_DECL.matcher(p.pattern()).find();
    }

    /**
     * True when neither side of the {@code [start, end)} span is alphanumeric —
     * i.e. the substring stands as a whole token. Mirrors what a {@code \b}
     * regex boundary would assert, but applied to a literal-string match.
     */
    private static boolean isWordBoundary(String s, int start, int end) {
        if (start > 0 && Character.isLetterOrDigit(s.charAt(start - 1))) return false;
        return end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
    }
}
