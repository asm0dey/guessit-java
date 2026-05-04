package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Set;

/**
 * When no {@code season} match exists but both a {@code year} and an
 * {@code episode} are present, emits a {@code season} match cloned from
 * the year match.
 *
 * <p>Mirrors python guessit's {@code year_to_season} rule: a filename like
 * {@code Show.2014.E03.mkv} should yield {@code season=2014} when there is no
 * explicit season marker.
 */
public final class YearSeason implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        if (ctx.matches.named("season").findAny().isPresent()) return;
        if (ctx.matches.named("episode").findAny().isEmpty()) return;
        ctx.matches.named("year").toList().forEach(year ->
            ctx.matches.add(new Match("season", year.value(), year.start(), year.end(),
                year.raw(), year.priority(), Set.copyOf(year.tags()), false)));
    }
}
