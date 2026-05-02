package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternMatcher {
    private PatternMatcher() {}

    public static List<Match> regex(String input, Pattern pattern, String name, RegexOpts opts) {
        var out = new ArrayList<Match>();
        var m = pattern.matcher(input);
        while (m.find()) {
            String raw = m.group();
            int start = m.start();
            int end = m.end();
            String valueText = hasGroup(m, "value") ? m.group("value") : raw;
            Object extracted = opts.valueExtractor().apply(valueText);
            Object formatted = opts.valueFormatter().apply(extracted);
            out.add(new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate()));
        }
        return out;
    }

    public static List<Match> string(String input, Set<String> needles, String name, StringOpts opts) {
        var out = new ArrayList<Match>();
        var hay = opts.caseSensitive() ? input : input.toLowerCase(java.util.Locale.ROOT);
        for (var raw : needles) {
            var n = opts.caseSensitive() ? raw : raw.toLowerCase(java.util.Locale.ROOT);
            int from = 0;
            while (true) {
                int idx = hay.indexOf(n, from);
                if (idx < 0) break;
                int end = idx + n.length();
                if (!opts.wholeWord() || isWordBoundary(hay, idx, end)) {
                    out.add(new Match(name, raw, idx, end, input.substring(idx, end),
                        opts.priority(), opts.tags(), opts.isPrivate()));
                }
                from = idx + 1;
            }
        }
        out.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return out;
    }

    private static boolean hasGroup(Matcher m, String name) {
        try { m.group(name); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean isWordBoundary(String s, int start, int end) {
        if (start > 0 && Character.isLetterOrDigit(s.charAt(start - 1))) return false;
        if (end < s.length() && Character.isLetterOrDigit(s.charAt(end))) return false;
        return true;
    }
}
