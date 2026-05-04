package io.guessit.rules.property;

import io.guessit.engine.DatePatterns;
import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;

import java.util.ArrayList;

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
    public int priority() {
        return 1100;
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var result = DatePatterns.search(input, ctx.options.dateYearFirst(), ctx.options.dateDayFirst());
        if (result.isEmpty()) return;
        var r = result.get();
        ctx.matches.add(new Match("date", r.date(), r.start(), r.end(),
                input.substring(r.start(), r.end()), 1100, null, false));
    }

    /**
     * Removes year/season/episode/crc32 matches that land inside the date span.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var dateMatch = ctx.matches.named("date").findFirst();
        if (dateMatch.isEmpty()) return;
        var dm = dateMatch.get();
        // Honor excludes/includes: when date is filtered out, keep the
        // overlapping year/season/episode candidates so they survive into
        // the output (e.g. "2015.01.31" with excludes=date emits year=2015).
        var excludes = ctx.options.excludes();
        var includes = ctx.options.includes();
        boolean dateFiltered = (!excludes.isEmpty() && excludes.contains("date"))
                || (!includes.isEmpty() && !includes.contains("date"));
        if (dateFiltered) return;
        var toRemove = new ArrayList<Match>();
        for (var m : ctx.matches.all().toList()) {
            if (m.equals(dm)) continue;
            if ("date".equals(m.name())) continue;
            if (m.start() >= dm.start()
                    && m.end() <= dm.end()
                    &&
                    (m.name().equals("year")
                            || m.name().equals("season")
                            || m.name().equals("episode")
                            || m.name().equals("crc32"))) {
                toRemove.add(m);
            }

        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
