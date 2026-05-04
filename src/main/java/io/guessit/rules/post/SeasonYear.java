package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Set;

/**
 * When no {@code year} match exists and a {@code season} value looks like a
 * plausible year (1900..currentYear+1), emits a {@code year} match cloned from
 * the season match.
 *
 * <p>Runs after {@link SeasonYearLink} so that {@code SeasonYearLink}'s wider
 * 1900..2100 window already handled any prior promotion; this processor is a
 * tighter guard that uses the dynamic current-year bound.
 */
public final class SeasonYear implements PostProcessor {
    private static final int MIN_YEAR = 1900;
    private static final int CUR = java.time.Year.now().getValue();

    @Override
    public void process(ParseContext ctx) {
        if (ctx.matches.named("year").findAny().isPresent()) return;
        ctx.matches.named("season").toList().forEach(season -> {
            if (!(season.value() instanceof Integer v)) return;
            if (v < MIN_YEAR || v > CUR + 1) return;
            ctx.matches.add(new Match("year", v, season.start(), season.end(),
                season.raw(), season.priority(), Set.copyOf(season.tags()), false));
        });
    }
}
