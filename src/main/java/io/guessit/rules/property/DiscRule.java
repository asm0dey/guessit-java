package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Validators;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts {@code disc} (multi-disc release indices: "Disc 1", "DVD 2",
 * "BD3", …).
 *
 * <p>The post-pass also fixes up matches that {@link SeasonEpisodeExtractor}
 * recognised via the {@code D} marker (e.g. "S01D02") — those get tagged
 * {@code disc-marker} as episodes and are renamed here once the dust has
 * settled. Doing the rename in this rule keeps disc-related logic in one
 * place rather than scattered across the season/episode extractor.
 */
public final class DiscRule implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)\\b(?:disc|dvd|vcd|bd|brd|bluray)[ ._-]*(\\d+)\\b");

    @Override public String name() { return "disc"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.DISC, null, m.start(), m.end(), m.group(), 1000, Set.of(), false);
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            ctx.matches.add(new Match(MatchName.DISC, v, m.start(1), m.end(1),
                m.group(1), 1000, Set.of(), false));
        }
    }

    /** Mirror Python RenameToDiscMatch: episodes from chains with `D` marker become discs. */
    @Override
    public void postProcess(ParseContext ctx) {
        var marked = ctx.matches.named(MatchName.EPISODE)
            .filter(m -> m.tags().contains("disc-marker"))
            .toList();
        if (marked.isEmpty()) return;
        var renamed = new ArrayList<Match>();
        for (var m : marked) {
            var newTags = new java.util.HashSet<>(m.tags());
            newTags.remove("disc-marker");
            renamed.add(new Match(MatchName.DISC, m.value(), m.start(), m.end(), m.raw(), m.priority(), newTags, m.isPrivate()));
        }
        for (var m : marked) ctx.matches.remove(m);
        for (var m : renamed) ctx.matches.add(m);
    }
}
