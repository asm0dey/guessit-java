package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts {@code week} from "Week 5"-style tokens. Validated against the
 * 1–52 ISO week range via {@link DatePatterns#validWeek}; out-of-range
 * candidates are dropped to avoid swallowing things like "Week 99".
 */
public final class WeekExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)week[ ._-]*(\\d{1,2})");

    @Override public String name() { return "week"; }

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
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            if (!DatePatterns.validWeek(v)) continue;
            ctx.matches.add(new Match(MatchName.WEEK, v, m.start(1), m.end(1),
                m.group(1), 1000, Set.of(), false));
        }
    }
}
