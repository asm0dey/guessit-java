package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.regex.Pattern;

public final class VersionExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)v(\\d+)");

    @Override public String name() { return "version"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s.substring(1)))
            .withValidator(m -> Validators.sepsBefore(input).test(m));
        for (var m : PatternMatcher.regex(input, PATTERN, "version", opts)) {
            ctx.matches.add(m);
        }
    }

    /** Replicates Python VersionValidator: drop version when not preceded by episode and not seps-surrounded. */
    @Override
    public void postProcess(ParseContext ctx) {
        var versions = ctx.matches.named("version").toList();
        var episodes = ctx.matches.named("episode").toList();
        var toRemove = new ArrayList<Match>();
        var input = ctx.input;
        for (var v : versions) {
            boolean precedingEpisode = episodes.stream().anyMatch(e -> e.end() == v.start());
            boolean surrounded = Validators.sepsSurround(input).test(v);
            if (!precedingEpisode && !surrounded) toRemove.add(v);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
