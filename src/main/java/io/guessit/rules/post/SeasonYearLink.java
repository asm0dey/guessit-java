package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Set;

/**
 * Promotes a season number that lies in a plausible year range to also be a
 * {@code year} match. Mirrors python guessit's {@code season_to_year} rule:
 * when {@code SxxExx} parses with a four-digit season (e.g. {@code S2014E18}
 * or {@code Looney Tunes 1940x01}) and no other year is present, emit a
 * {@code year=value} match alongside the season.
 *
 * <p>Skips emission when an explicit year already exists or the season value
 * is outside the {@link #MIN_YEAR}..{@link #MAX_YEAR} window — small season
 * numbers ("S2" → 2) shouldn't masquerade as years.
 */
public final class SeasonYearLink implements PostProcessor {
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2100;

    @Override
    public void process(ParseContext ctx) {
        if (ctx.matches.named(MatchName.YEAR).findAny().isPresent()) return;
        var seasons = ctx.matches.named(MatchName.SEASON).toList();
        for (var s : seasons) {
            if (!(s.value() instanceof Integer i)) continue;
            if (i < MIN_YEAR || i > MAX_YEAR) continue;
            ctx.matches.add(new Match(MatchName.YEAR, i, s.start(), s.end(), s.raw(),
                s.priority(), Set.of("season-derived"), false));
            return;
        }
    }
}
