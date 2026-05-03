package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

public final class WeakDuplicateExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})(\\d{2})(?!\\d)");

    @Override public String name() { return "weak_duplicate"; }
    @Override public int priority() { return 700; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        if (Boolean.TRUE.equals(ctx.options.episodePreferNumber())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            var span = new Match("weak", null, m.start(), m.end(), m.group(), 700, Set.of(), false);
            if (!seps.test(span)) continue;
            int s = Integer.parseInt(m.group(1));
            int e = Integer.parseInt(m.group(2));
            ctx.matches.add(new Match("season", s, m.start(1), m.end(1),
                m.group(1), 700, Set.of("weak-episode", "weak-duplicate", "coexist"), false));
            ctx.matches.add(new Match("episode", e, m.start(2), m.end(2),
                m.group(2), 700, Set.of("weak-episode", "weak-duplicate", "coexist"), false));
        }
    }

    /** Replicates RemoveWeakDuplicate: drop the weak-duplicate pair when a strong SxxExx exists. */
    @Override
    public void postProcess(ParseContext ctx) {
        // Drop weak-episode matches that overlap with weak-duplicate matches
        var weakDuplicates = ctx.matches.all().filter(m -> m.tags().contains("weak-duplicate")).toList();
        if (!weakDuplicates.isEmpty()) {
            var toRemove = ctx.matches.all()
                .filter(m -> m.tags().contains("weak-episode") && !m.tags().contains("weak-duplicate"))
                .filter(m -> weakDuplicates.stream().anyMatch(wd -> wd.overlaps(m)))
                .toList();
            for (var m : toRemove) ctx.matches.remove(m);
        }

        boolean strongPresent = ctx.matches.named("episode").anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("episode-word"))
            || ctx.matches.named("season").anyMatch(m -> m.tags().contains("SxxExx") || m.tags().contains("season-word"));
        if (!strongPresent) return;
        var toRemove = new ArrayList<Match>();
        for (var name : new String[]{"season", "episode"}) {
            for (var m : ctx.matches.named(name).toList()) {
                if (m.tags().contains("weak-duplicate")) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
