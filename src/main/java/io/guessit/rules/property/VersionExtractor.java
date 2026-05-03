package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Extracts release {@code version} (the {@code v2}, {@code v3} suffix on
 * scene-style names).
 *
 * <p>The regex is permissive — any {@code v\d+} substring becomes a candidate.
 * The post-pass enforces the real rule: a version match must either sit
 * immediately after an episode match (forming {@code "S01E01v2"}) or be
 * separator-surrounded; bare {@code "v2"} inside a title is dropped.
 */
public final class VersionExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)v(\\d+)");

    @Override
    public String name() {
        return "version";
    }

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

    /**
     * Replicates Python VersionValidator: drop version when not preceded by episode and not seps-surrounded.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var versions = ctx.matches.named("version").toList();
        var episodes = ctx.matches.named("episode").toList();
        var toRemove = new ArrayList<Match>();
        var input = ctx.input;
        for (var v : versions) {
            boolean precedingEpisode = episodes.stream().anyMatch(e -> e.end() == v.start() || e.end() + 1 == v.start() && Character.toLowerCase(input.charAt(e.end())) == 'v');
            boolean surrounded = Validators.sepsSurround(input).test(v);
            if (!precedingEpisode && !surrounded) toRemove.add(v);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
