package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.*;
import io.guessit.engine.date.DatePatterns;

import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Extracts {@code week} from "Week 5"-style tokens. Validated against the
 * 1–52 ISO week range via {@link DatePatterns#validWeek}; out-of-range
 * candidates are dropped to avoid swallowing things like "Week 99".
 */
public final class WeekExtractor implements Extractor {

    private static final String GRP_WEEK = "weekNum";

    private static final SiftPattern<Fragment> SEPARATORS = anyOf(
            literal(" "), literal("."), literal("_"), literal("-")
    );

    private static final Pattern PATTERN = Pattern.compile(
            filteringWith(SiftGlobalFlag.CASE_INSENSITIVE).fromAnywhere()
                    .of(literal("week"))
                    .followedBy(zeroOrMore().of(SEPARATORS))
                    .then()
                    .namedCapture(capture(GRP_WEEK, between(1, 2).digits()))
                    .shake()
    );

    @Override
    public String name() {
        return "week";
    }

    @Override
    public String description() {
        return "week tokens (W12, Week 12, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);

        while (m.find()) {
            var head = new Match(MatchName.WEEK, null, m.start(), m.end(), m.group(), 1000, Set.of(), false);

            if (seps.test(head)) {
                int v = Integer.parseInt(m.group(GRP_WEEK));

                if (DatePatterns.validWeek(v)) {
                    ctx.matches.add(new Match(MatchName.WEEK, v, m.start(GRP_WEEK), m.end(GRP_WEEK),
                            m.group(GRP_WEEK), 1000, Set.of(), false));
                }
            }
        }
    }
}