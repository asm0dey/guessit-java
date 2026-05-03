package io.guessit.rules.property;

import io.guessit.engine.DatePatterns;
import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;

import java.util.ArrayList;

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
