package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.regex.Pattern;

public final class EpisodeFormatExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)Minisodes?");

    @Override public String name() { return "episode_format"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(s -> "Minisode")
            .withValidator(m -> Validators.sepsSurround(input).test(m));
        for (var m : PatternMatcher.regex(input, PATTERN, "episode_format", opts)) {
            ctx.matches.add(m);
        }
    }
}
