package io.guessit.rules.property;

import io.guessit.engine.date.DatePatterns;
import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;

import java.util.Set;

/**
 * Extracts {@code date} via {@link DatePatterns#search}.
 *
 * <p>Priority 1100 (above the default 1000) so the date wins overlap against
 * the year/season/episode digits embedded inside it. The post-pass
 * additionally removes any year/season/episode/crc32 match whose span sits
 * fully inside the date — those are now redundant components of the date,
 * not standalone properties.
 */
public final class DateExtractor implements Extractor {

    @Override
    public String name() {
        return "date";
    }

    @Override
    public String description() {
        return "date (YYYY-MM-DD, DD-MM-YYYY, …)";
    }

    @Override
    public int priority() {
        return 1100;
    }

    @Override
    public void extract(ParseContext ctx) {
        if (isDateFiltered(ctx)) {
            return;
        }

        var input = ctx.input;

        DatePatterns.search(input, ctx.options.dateYearFirst(), ctx.options.dateDayFirst())
                .ifPresent(r -> ctx.matches.add(new Match(
                        MatchName.DATE, r.date(), r.start(), r.end(),
                        input.substring(r.start(), r.end()), priority(), Set.of(), false)
                ));
    }

    /**
     * Removes year/season/episode/crc32 matches that land inside the date span.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        if (isDateFiltered(ctx)) {
            return;
        }

        ctx.matches.named(MatchName.DATE)
                .findFirst()
                .ifPresent(dateMatch -> removeRedundantInnerMatches(ctx, dateMatch));
    }

    private boolean isDateFiltered(ParseContext ctx) {
        var excludes = ctx.options.excludes();
        var includes = ctx.options.includes();
        return (!excludes.isEmpty() && excludes.contains("date"))
                || (!includes.isEmpty() && !includes.contains("date"));
    }

    private void removeRedundantInnerMatches(ParseContext ctx, Match dateMatch) {
        var toRemove = ctx.matches.all()
                .filter(m -> isRedundantInnerMatch(m, dateMatch))
                .toList();

        toRemove.forEach(ctx.matches::remove);
    }

    private boolean isRedundantInnerMatch(Match m, Match dateMatch) {
        if (m.equals(dateMatch) || m.name() == MatchName.DATE) {
            return false;
        }

        boolean isInsideDateSpan = m.start() >= dateMatch.start() && m.end() <= dateMatch.end();
        boolean isTargetType = m.name() == MatchName.YEAR
                || m.name() == MatchName.SEASON
                || m.name() == MatchName.EPISODE
                || m.name() == MatchName.CRC32;

        return isInsideDateSpan && isTargetType;
    }
}