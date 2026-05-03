package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

public final class DiscRule implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)\\b(?:disc|dvd|vcd|bd|brd|bluray)[ ._-]*(\\d+)\\b");

    @Override public String name() { return "disc"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            var head = new Match("disc", null, m.start(), m.end(), m.group(), 1000, Set.of(), false);
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            ctx.matches.add(new Match("disc", v, m.start(1), m.end(1),
                m.group(1), 1000, Set.of(), false));
        }
    }

    /** Mirror Python RenameToDiscMatch: episodes from chains with `D` marker become discs. */
    @Override
    public void postProcess(ParseContext ctx) {
        var marked = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("disc-marker"))
            .toList();
        if (marked.isEmpty()) return;
        var renamed = new ArrayList<Match>();
        for (var m : marked) {
            var newTags = new java.util.HashSet<>(m.tags());
            newTags.remove("disc-marker");
            renamed.add(new Match("disc", m.value(), m.start(), m.end(), m.raw(), m.priority(), newTags, m.isPrivate()));
        }
        for (var m : marked) ctx.matches.remove(m);
        for (var m : renamed) ctx.matches.add(m);
    }
}
