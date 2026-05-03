package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Comparator;
import java.util.Set;

/**
 * Expand range pairs of {@code season} or {@code episode} matches into the full
 * sequence: e.g. {@code E01-04} (matches [1, 4]) becomes [1, 2, 3, 4].
 *
 * <p>Mirror's Python guessit's behaviour: when two adjacent season or episode
 * matches are separated only by separator characters plus a range keyword
 * (-, ~, "to", "a"), fill the missing integer values in between. Skips when
 * the gap is wider than {@link #MAX_GAP} characters or values aren't strictly
 * ascending.
 */
public final class RangeFiller implements PostProcessor {

    private static final int MAX_GAP = 6;
    private static final Set<String> RANGE_KEYWORDS = Set.of("-", "~", "to", "a");

    @Override
    public void process(ParseContext ctx) {
        fillProp(ctx, "episode");
        fillProp(ctx, "season");
    }

    private static void fillProp(ParseContext ctx, String prop) {
        var matches = ctx.matches.named(prop)
            .filter(m -> m.value() instanceof Integer)
            .sorted(Comparator.comparingInt(Match::start))
            .toList();
        if (matches.size() < 2) return;
        var input = ctx.input;
        var fills = new java.util.ArrayList<Match>();
        for (int i = 0; i + 1 < matches.size(); i++) {
            var prev = matches.get(i);
            var next = matches.get(i + 1);
            int prevVal = (Integer) prev.value();
            int nextVal = (Integer) next.value();
            if (nextVal <= prevVal + 1) continue;
            if (next.start() < prev.end()) continue;
            int gapLen = next.start() - prev.end();
            if (gapLen <= 0 || gapLen > MAX_GAP) continue;
            var gap = input.substring(prev.end(), next.start());
            if (!isRangeGap(gap)) continue;
            // Avoid double-fill: skip if a range-fill match already covers this gap.
            int prevEnd = prev.end();
            int nextStart = next.start();
            boolean alreadyFilled = ctx.matches.named(prop)
                .anyMatch(m -> m.tags().contains("range-fill")
                    && m.start() >= prevEnd && m.end() <= nextStart);
            if (alreadyFilled) continue;
            for (int v = prevVal + 1; v < nextVal; v++) {
                fills.add(new Match(prop, v, prev.end(), next.start(),
                    String.valueOf(v), 1000, Set.of("range-fill"), false));
            }
        }
        for (var m : fills) ctx.matches.add(m);
    }

    private static boolean isRangeGap(String gap) {
        // gap chars must be only separators and at most one range keyword
        var lc = gap.toLowerCase(java.util.Locale.ROOT);
        // strip leading/trailing separators
        int s = 0, e = lc.length();
        while (s < e && isSep(lc.charAt(s))) s++;
        while (e > s && isSep(lc.charAt(e - 1))) e--;
        if (s == e) return false; // only separators, no keyword - not a range
        var core = lc.substring(s, e);
        if (RANGE_KEYWORDS.contains(core)) return true;
        // Tolerate trailing season/episode marker on the next match: ".to.s", ".to.e".
        if (core.length() > 1) {
            char last = core.charAt(core.length() - 1);
            if (last == 's' || last == 'e') {
                var trimmed = core.substring(0, core.length() - 1);
                int e2 = trimmed.length();
                while (e2 > 0 && isSep(trimmed.charAt(e2 - 1))) e2--;
                return RANGE_KEYWORDS.contains(trimmed.substring(0, e2));
            }
        }
        return false;
    }

    private static boolean isSep(char c) {
        return c == ' ' || c == '.' || c == '_';
    }
}
