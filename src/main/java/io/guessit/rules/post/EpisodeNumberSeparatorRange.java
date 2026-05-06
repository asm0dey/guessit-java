package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Expands bare episode ranges like {@code 16-20} into the full sequence
 * {@code [16, 17, 18, 19, 20]}.
 *
 * <p>This post-processor handles the case where the text following an episode
 * match contains a range separator ({@code -}, {@code ~}, or {@code to})
 * immediately followed by an integer, but that integer was <em>not</em>
 * independently parsed as an episode match (e.g. because no "Episode" keyword
 * prefixes the second number). It detects the trailing number, validates the
 * range, and inserts episode matches for the endpoint and all intermediate
 * values.
 *
 * <p>Bounded by {@link #MAX_JUMP} to avoid runaway expansion on year-like
 * differences (e.g. {@code 1990-2001} should not produce 12 episodes).
 *
 * <p>Run after {@link RangeFiller} so that ranges between two existing
 * episode matches are already filled before this rule looks for single-sided
 * ranges.
 */
public final class EpisodeNumberSeparatorRange implements PostProcessor {

    private static final int MAX_JUMP = 20;

    /**
     * Matches a range separator (-, ~, "to") with optional surrounding
     * separator characters (_ . space), followed by an integer.
     * Group 1 = the integer digits.
     */
    private static final Pattern RANGE_THEN_NUM = Pattern.compile(
        "(?i)[\\s._]*[-~][\\s._]*(\\d+)|[\\s._]+to[\\s._]+(\\d+)");
    public static final MatchName EPISODE = MatchName.EPISODE;

    @Override
    public void process(ParseContext ctx) {
        var eps = ctx.matches.named(EPISODE)
            .filter(m -> !m.isPrivate() && m.value() instanceof Integer)
            .sorted(Comparator.comparingInt(Match::start))
            .toList();
        var fills = new ArrayList<Match>();
        for (var a : eps) tryExtendRange(ctx, a, fills);
        for (var m : fills) ctx.matches.add(m);
    }

    private record RangeNumber(int value, int start, int end) {}

    private static void tryExtendRange(ParseContext ctx, Match a, java.util.List<Match> fills) {
        int va = (Integer) a.value();
        int scanFrom = a.end();
        if (scanFrom >= ctx.input.length()) return;
        var matcher = RANGE_THEN_NUM.matcher(ctx.input);
        matcher.region(scanFrom, ctx.input.length());
        if (!matcher.lookingAt()) return;
        var span = extractRangeNumber(matcher);
        if (span == null) return;
        if (span.value() <= va || span.value() - va > MAX_JUMP) return;
        if (vbAlreadyPresent(ctx, span)) return;
        if (alreadyFilled(ctx, a, span.end())) return;

        // Add vb itself as an episode match.
        fills.add(new Match(EPISODE, span.value(), span.start(), span.end(),
            ctx.input.substring(span.start(), span.end()), 1000, Set.of("range-fill"), false));
        // Add intermediate values va+1 .. vb-1 (zero-width, anchored at numStart).
        for (int v = va + 1; v < span.value(); v++) {
            fills.add(new Match(EPISODE, v, span.start(), span.start(),
                "", 1000, Set.of("range-fill"), false));
        }
    }

    /** Extract the integer + span from whichever capture group matched. */
    private static RangeNumber extractRangeNumber(java.util.regex.Matcher matcher) {
        String numStr = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        int vb;
        try { vb = Integer.parseInt(numStr); }
        catch (NumberFormatException _) { return null; }
        int numStart = matcher.group(1) != null ? matcher.start(1) : matcher.start(2);
        int numEnd   = matcher.group(1) != null ? matcher.end(1)   : matcher.end(2);
        return new RangeNumber(vb, numStart, numEnd);
    }

    /** True when vb is already an episode match at that position —
     *  RangeFiller already handled it (or will). */
    private static boolean vbAlreadyPresent(ParseContext ctx, RangeNumber span) {
        return ctx.matches.named(EPISODE)
            .anyMatch(m -> m.value() instanceof Integer iv && iv == span.value()
                && m.start() >= span.start() && m.end() <= span.end());
    }

    /** True when an existing episode range-fill already covers the gap. */
    private static boolean alreadyFilled(ParseContext ctx, Match a, int numEnd) {
        return ctx.matches.named(EPISODE)
            .anyMatch(m -> m.tags().contains("range-fill")
                && m.start() >= a.end() && m.end() <= numEnd);
    }
}
