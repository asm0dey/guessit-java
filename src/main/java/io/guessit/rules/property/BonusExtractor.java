package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects bonus feature numbers from {@code x\d+} patterns, e.g. {@code x05}.
 *
 * <p>Conflict guard: if a {@code video_codec} match (e.g. x264, x265) or a
 * strong (non-weak) {@code episode} match overlaps the same span, the bonus
 * candidate is silently dropped so the stronger extractor wins.
 *
 * <p>postProcess builds a {@code bonus_title} from the trailing hole in the
 * same filepart marker after the bonus match.
 */
public final class BonusExtractor implements Extractor {
    // Matches "x" followed by digits; case-insensitive so "X05" also works.
    private static final Pattern P = Pattern.compile("(?i)x(?<n>\\d+)");

    @Override public String name() { return "bonus"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = P.matcher(input);
        while (m.find()) {
            // Use the full match span for separator-surround check.
            var head = new Match(MatchName.BONUS, null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;

            // Skip if any video_codec or strong episode overlaps this span.
            boolean conflict = ctx.matches.snapshot().stream().anyMatch(x ->
                (x.name() == MatchName.VIDEO_CODEC ||
                 (x.name() == MatchName.EPISODE && !x.tags().contains("weak-episode")))
                && x.start() < m.end() && x.end() > m.start());
            if (conflict) continue;

            // Span covers the full "xNN" so the leading 'x' doesn't leak into
            // the surrounding title hole.
            ctx.matches.add(new Match(MatchName.BONUS, Integer.parseInt(m.group("n")),
                m.start(), m.end(), m.group(), priority(), Set.of(), false));
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var bonusMatches = ctx.matches.named(MatchName.BONUS).toList();
        if (bonusMatches.isEmpty()) return;
        if (ctx.matches.named(MatchName.BONUS_TITLE).findAny().isPresent()) return;

        for (var bonus : bonusMatches) {
            // Find the filepart (path marker) that contains this bonus match.
            var fpOpt = ctx.markers.stream()
                .filter(mk -> mk.name().equals("path") && mk.covers(bonus.start(), bonus.end()))
                .findFirst();
            if (fpOpt.isEmpty()) continue;
            var fp = fpOpt.get();

            // Look for trailing hole after the bonus match within the filepart.
            // Ignore private matches and weak-episode candidates: weak episodes
            // (e.g. "60" right after "x02") would otherwise carve the leading
            // numeric out of the bonus_title; WeakEpisodeExtractor.postProcess
            // hasn't dropped them yet at this point and they won't survive.
            var holes = Holes.compute(
                ctx.input,
                bonus.end(),
                fp.end(),
                ctx.matches.snapshot(),
                m -> m.isPrivate() || m.tags().contains("weak-episode"),
                null,               // no sep splitting — keep continuous text
                Formatters::cleanup
            );

            if (holes.isEmpty()) continue;

            // Use the first (and usually only) trailing hole as bonus_title.
            var hole = holes.getFirst();
            var title = hole.value();
            if (title == null || title.isBlank()) continue;

            ctx.matches.add(new Match(MatchName.BONUS_TITLE, title,
                hole.start, hole.end, hole.raw(), priority(), Set.of(), false));
            break; // one bonus_title per parse
        }
    }
}
