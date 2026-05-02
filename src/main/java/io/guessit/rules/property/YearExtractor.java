package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class YearExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("\\d{4}");

    @Override public String name() { return "year"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s))
            .withValidator(m -> {
                if (!Validators.sepsSurround(input).test(m)) return false;
                int v = (Integer) m.value();
                return 1920 <= v && v < 2030;
            });
        for (var match : PatternMatcher.regex(input, PATTERN, "year", opts)) {
            ctx.matches.add(match);
        }
    }

    /** Replicates Python rules/properties/date.py:KeepMarkedYearInFilepart. */
    @Override
    public void postProcess(ParseContext ctx) {
        var years = ctx.matches.named("year").toList();
        if (years.size() <= 1) return;

        var toRemove = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var inPart = years.stream()
                .filter(y -> filepart.covers(y.start(), y.end()))
                .toList();
            if (inPart.size() <= 1) continue;

            var grouped = new ArrayList<Match>();
            var ungrouped = new ArrayList<Match>();
            for (var y : inPart) {
                boolean inGroup = ctx.markers.stream()
                    .anyMatch(mk -> "group".equals(mk.name()) && mk.covers(y.start(), y.end()));
                (inGroup ? grouped : ungrouped).add(y);
            }
            if (!grouped.isEmpty() && !ungrouped.isEmpty()) {
                toRemove.addAll(ungrouped);
                if (grouped.size() > 1) toRemove.addAll(grouped.subList(1, grouped.size()));
            } else if (grouped.isEmpty()) {
                // Keep first ungrouped (for title); drop everything from index 2 onward.
                if (ungrouped.size() > 2) toRemove.addAll(ungrouped.subList(2, ungrouped.size()));
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
