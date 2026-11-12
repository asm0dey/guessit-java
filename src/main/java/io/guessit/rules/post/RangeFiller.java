package io.guessit.rules.post;

import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.engine.SiftCompiledPattern;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;
import static com.mirkoddd.sift.core.SiftPatterns.literal;

/**
 * Expand range pairs of {@code season} or {@code episode} matches into the full
 * sequence: e.g. {@code E01-04} (matches [1, 4]) becomes [1, 2, 3, 4].
 */
public final class RangeFiller implements PostProcessor {

    private static final int MAX_GAP = 6;
    private static final int MAX_JUMP = 20;
    private static final int FILL_CONFIDENCE = 1000;

    private static final String TAG_RANGE_FILL = "range-fill";

    private static final SiftCompiledPattern GAP_PATTERN = buildGapPattern();

    private static SiftCompiledPattern buildGapPattern() {
        var keywords = anyOf(literal("-"), literal("~"), literal("to"), literal("a"));

        var sep = anyOf(literal(" "), literal("."), literal("_"));
        var optSeps = zeroOrMore().of(sep);

        var optSuffix = optional().of(
                fromAnywhere().of(optSeps).then().exactly(1).of(anyOf(literal("s"), literal("e")))
        );

        var standardGap = fromAnywhere().of(keywords).then().of(optSuffix);

        var seGap = fromAnywhere()
                .of(anyOf(literal("-"), literal("~")))
                .followedBy('s')
                .then().between(1, 3).digits()
                .then().optional().character('e');

        return Sift.filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromStart()
                .of(optSeps)
                .then().exactly(1).of(anyOf(standardGap, seGap))
                .then().of(optSeps)
                .andNothingElse()
                .sieve();
    }

    @Override
    public String description() {
        return "fill numeric ranges between season/episode endpoints";
    }

    @Override
    public void process(ParseContext ctx) {
        ctx.trace.subStep("Stage 1: fill missing values in episode ranges");
        fillProp(ctx, MatchName.EPISODE);

        ctx.trace.subStep("Stage 2: fill missing values in season ranges");
        fillProp(ctx, MatchName.SEASON);
    }

    private static void fillProp(ParseContext ctx, MatchName prop) {
        var matches = ctx.matches.named(prop)
                .filter(m -> m.value() instanceof Integer)
                .sorted(Comparator.comparingInt(Match::start))
                .toList();

        if (matches.size() < 2) return;

        var input = ctx.input;
        var fills = new ArrayList<Match>();

        for (int i = 0; i + 1 < matches.size(); i++) {
            var prev = matches.get(i);
            var next = matches.get(i + 1);

            if (!isFillablePair(ctx, prop, input, prev, next)) continue;

            int prevVal = (Integer) prev.value();
            int nextVal = (Integer) next.value();

            for (int v = prevVal + 1; v < nextVal; v++) {
                fills.add(new Match(prop, v, prev.end(), next.start(),
                        String.valueOf(v), FILL_CONFIDENCE, Set.of(TAG_RANGE_FILL), false));
            }
        }

        fills.forEach(ctx.matches::add);
    }

    private static boolean isFillablePair(ParseContext ctx, MatchName prop, String input, Match prev, Match next) {
        int prevVal = (Integer) prev.value();
        int nextVal = (Integer) next.value();

        if (nextVal <= prevVal + 1) return false;
        if (nextVal - prevVal > MAX_JUMP) return false;
        if (next.start() < prev.end()) return false;

        int gapLen = next.start() - prev.end();
        if (gapLen <= 0 || gapLen > MAX_GAP) return false;

        String gap = input.substring(prev.end(), next.start());
        if (!GAP_PATTERN.matchesEntire(gap)) return false;

        int prevEnd = prev.end();
        int nextStart = next.start();

        return ctx.matches.named(prop)
                .noneMatch(m -> m.tags().contains(TAG_RANGE_FILL)
                        && m.start() >= prevEnd && m.end() <= nextStart);
    }
}