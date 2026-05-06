package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

/**
 * Decide {@code type} (movie|episode) from surviving matches and emit a
 * zero-width {@code type} match at the end of the input, mirroring Python
 * guessit's {@code TypeProcessor}. Then demote any {@code episode_title}
 * (without the {@code alternative-replaced} tag) back to {@code alternative_title}
 * when the chosen type is not {@code episode} (Python {@code RenameEpisodeTitleWhenMovieType}).
 */
public final class TypeProcessor implements PostProcessor {

    public static final MatchName EPISODE = MatchName.EPISODE;
    private static final String EPISODE_TYPE = "episode";
    private static final String MOVIE_TYPE = "movie";

    @Override
    public void process(ParseContext ctx) {
        var type = decide(ctx);
        var len = ctx.input.length();
        ctx.matches.add(Match.of(MatchName.TYPE, type, len, len, ""));
        if (!EPISODE_TYPE.equals(type)) {
            var toRename = ctx.matches.named(MatchName.EPISODE_TITLE)
                .filter(m -> !m.tags().contains("alternative-replaced"))
                .toList();
            for (var m : toRename) {
                ctx.matches.replace(m, new Match(MatchName.ALTERNATIVE_TITLE, m.value(),
                    m.start(), m.end(), m.raw(), m.priority(), m.tags(), m.isPrivate()));
            }
        }
    }

    private static String decide(ParseContext ctx) {
        var optType = ctx.options.type();
        if (optType != null) return optType;
        if (anyNamed(ctx, MatchName.EPISODE) || anyNamed(ctx, MatchName.SEASON)
                || anyNamed(ctx, MatchName.EPISODE_DETAILS) || anyNamed(ctx, MatchName.ABSOLUTE_EPISODE)) {
            return EPISODE_TYPE;
        }
        if (anyNamed(ctx, MatchName.FILM)) return MOVIE_TYPE;
        var hasYear = anyNamed(ctx, MatchName.YEAR);
        if (anyNamed(ctx, MatchName.DATE) && !hasYear) return EPISODE_TYPE;
        if (anyNamed(ctx, MatchName.BONUS) && !hasYear) return EPISODE_TYPE;
        var hasCrc = anyNamed(ctx, MatchName.CRC32);
        var anyAnimeRg = ctx.matches.named(MatchName.RELEASE_GROUP).anyMatch(m -> m.tags().contains("anime"));
        if (hasCrc && anyAnimeRg) return EPISODE_TYPE;
        return MOVIE_TYPE;
    }

    private static boolean anyNamed(ParseContext ctx, MatchName name) {
        return ctx.matches.named(name).anyMatch(m -> !m.isPrivate());
    }

}
