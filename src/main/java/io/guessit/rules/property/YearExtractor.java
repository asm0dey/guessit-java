package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Extracts release {@code year} as a 4-digit integer in [1920, 2030).
 *
 * <p>The extractor is intentionally permissive — any 4-digit run that is
 * separator-surrounded and falls in the year range becomes a candidate.
 * Resolution between multiple candidates in the same filepart happens in
 * {@link #postProcess}, not at extract time, because year resolution
 * depends on which other markers (groups) survived.
 */
public final class YearExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("\\d{4}");

    @Override
    public String name() {
        return "year";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
                .withValue(Integer::valueOf)
                .withValidator(m -> {
                    if (!Validators.sepsSurround(input).test(m)) return false;
                    int v = (Integer) m.value();
                    return 1920 <= v && v < 2030;
                });
        for (var match : PatternMatcher.regex(input, PATTERN, "year", opts)) {
            ctx.matches.add(match);
        }
    }

    /**
     * Replicates Python rules/properties/date.py:KeepMarkedYearInFilepart.
     *
     * <p>Per filepart, partition surviving year matches by whether they sit
     * inside a bracketed group:
     * <ul>
     *   <li>If both grouped and ungrouped years exist → drop all ungrouped
     *       (the releaser's explicit grouping wins) and keep only the first
     *       grouped year.</li>
     *   <li>If only ungrouped years exist and there are more than two → keep
     *       the first two and drop the rest. Two is the empirical guessit
     *       cap; more than that is almost always noise.</li>
     * </ul>
     */
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
                // Drop the FIRST ungrouped year so it can fall into the title hole;
                // the second year is the actual release year. Mirrors python
                // KeepMarkedYearInFilepart: "Keep first year for title".
                toRemove.add(ungrouped.get(0));
                if (ungrouped.size() > 2) toRemove.addAll(ungrouped.subList(2, ungrouped.size()));
            }

        }
        for (var m : toRemove) ctx.matches.remove(m);

        // After dropping a leading year, also kill any weak-duplicate
        // season/episode matches that landed inside that year's 4-digit span.
        // WeakDuplicateExtractor's post-pass runs after us and uses the
        // current year set as a guard; once year[0] is gone its NN/NN split
        // is no longer protected, and "2012.2009..." would parse as
        // season=20/episode=12.
        for (var dropped : toRemove) {
            var weakDups = ctx.matches.all()
                    .filter(m -> m.tags().contains("weak-duplicate"))
                    .filter(m -> "season".equals(m.name()) || "episode".equals(m.name()))
                    .filter(dropped::overlaps)
                    .toList();
            for (var m : weakDups) ctx.matches.remove(m);
        }
    }
}
