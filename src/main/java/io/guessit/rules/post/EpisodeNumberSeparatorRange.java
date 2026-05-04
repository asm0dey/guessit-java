package io.guessit.rules.post;

import io.guessit.engine.Match;
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
    public static final String EPISODE = "episode";

    @Override
    public void process(ParseContext ctx) {
        var eps = ctx.matches.named(EPISODE)
            .filter(m -> !m.isPrivate() && m.value() instanceof Integer)
            .sorted(Comparator.comparingInt(Match::start))
            .toList();

        var fills = new ArrayList<Match>();

        for (var a : eps) {
            int va = (Integer) a.value();
            int scanFrom = a.end();

            // Don't scan past end of input.
            if (scanFrom >= ctx.input.length()) continue;

            var matcher = RANGE_THEN_NUM.matcher(ctx.input);
            matcher.region(scanFrom, ctx.input.length());
            if (!matcher.lookingAt()) continue;

            // Extract the integer from whichever capture group matched.
            String numStr = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int vb;
            try { vb = Integer.parseInt(numStr); }
            catch (NumberFormatException _) { continue; }

            if (vb <= va || vb - va > MAX_JUMP) continue;

            // The end position of the matched number in the input.
            int numStart = matcher.group(1) != null ? matcher.start(1) : matcher.start(2);
            int numEnd   = matcher.group(1) != null ? matcher.end(1)   : matcher.end(2);

            // Check whether vb is already an episode match at that position —
            // if so, RangeFiller already handled this (or will handle it).
            boolean vbAlreadyPresent = ctx.matches.named(EPISODE)
                .anyMatch(m -> m.value() instanceof Integer iv && iv == vb
                    && m.start() >= numStart && m.end() <= numEnd);
            if (vbAlreadyPresent) continue;

            // Check that there is no existing episode range-fill covering the gap.
            boolean alreadyFilled = ctx.matches.named(EPISODE)
                .anyMatch(m -> m.tags().contains("range-fill")
                    && m.start() >= a.end() && m.end() <= numEnd);
            if (alreadyFilled) continue;

            // Add vb itself as an episode match.
            fills.add(new Match(EPISODE, vb, numStart, numEnd,
                ctx.input.substring(numStart, numEnd), 1000, Set.of("range-fill"), false));

            // Add intermediate values va+1 .. vb-1 (zero-width, anchored at numStart).
            for (int v = va + 1; v < vb; v++) {
                fills.add(new Match(EPISODE, v, numStart, numStart,
                    "", 1000, Set.of("range-fill"), false));
            }
        }

        for (var m : fills) ctx.matches.add(m);
    }
}
