package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import io.guessit.engine.*;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

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

    public static final String EXTRACTOR_NAME = "version";
    private static final String GRP_VAL = "val";

    private static final Pattern PATTERN = buildPattern();

    private static Pattern buildPattern() {
        var sift = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .exactly(1).character('v')
                .then().namedCapture(capture(GRP_VAL, oneOrMore().digits()));

        return Pattern.compile(sift.shake());
    }

    @Override
    public String name() {
        return EXTRACTOR_NAME;
    }

    @Override
    public String description() {
        return "version (v2, v3, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var m = PATTERN.matcher(input);

        while (m.find()) {
            int start = m.start();

            boolean isValidPrefix = (start == 0) || Seps.isSep(input.charAt(start - 1)) || Character.isDigit(input.charAt(start - 1));

            if (isValidPrefix) {
                var raw = m.group();
                int val = Integer.parseInt(m.group(GRP_VAL));

                ctx.matches.add(new Match(MatchName.VERSION, val, start, m.end(), raw, priority(), Set.of(), false));
            }
        }
    }

    /**
     * Replicates Python VersionValidator: drop version when not preceded by episode and not seps-surrounded.
     */
    @Override
    public void postProcess(ParseContext ctx) {
        var versions = ctx.matches.named(MatchName.VERSION).toList();
        if (versions.isEmpty()) return;

        var episodes = ctx.matches.named(MatchName.EPISODE).toList();
        var seps = Validators.sepsSurround(ctx.input);

        var toRemove = versions.stream()
                .filter(v -> !isValidVersionContext(v, episodes, seps, ctx.input))
                .toList();

        toRemove.forEach(ctx.matches::remove);
    }

    private boolean isValidVersionContext(Match version, List<Match> episodes, Predicate<Match> seps, String input) {
        if (seps.test(version)) {
            return true;
        }

        return episodes.stream().anyMatch(e -> isPrecededByEpisode(version, e, input));
    }

    private boolean isPrecededByEpisode(Match version, Match episode, String input) {
        if (episode.end() == version.start()) {
            return true;
        }

        return (episode.end() + 1 == version.start())
                && Character.toLowerCase(input.charAt(episode.end())) == 'v';
    }
}