package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.engine.MatchName;

import java.util.regex.Pattern;

/**
 * Extracts {@code episode_format}. Currently only "Minisode(s)" is recognised
 * — guessit's catalogue here is small and stable; new formats can be added
 * by widening the pattern alternation.
 */
public final class EpisodeFormatExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)Minisodes?");

    @Override public String name() { return MatchName.EPISODE_FORMAT.toString().toLowerCase(); }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(_ -> "Minisode")
            .withValidator(m -> Validators.sepsSurround(input).test(m));
        for (var m : PatternMatcher.regex(input, PATTERN, MatchName.EPISODE_FORMAT, opts)) {
            ctx.matches.add(m);
        }
    }
}
