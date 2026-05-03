package io.guessit.rules.post;

import io.guessit.engine.Match;
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
    @Override
    public void process(ParseContext ctx) {
        var type = decide(ctx);
        var len = ctx.input.length();
        ctx.matches.add(Match.of("type", type, len, len, ""));
        if (!"episode".equals(type)) {
            var toRename = ctx.matches.named("episode_title")
                .filter(m -> !m.tags().contains("alternative-replaced"))
                .toList();
            for (var m : toRename) {
                ctx.matches.replace(m, new Match("alternative_title", m.value(),
                    m.start(), m.end(), m.raw(), m.priority(), m.tags(), m.isPrivate()));
            }
        }
    }

    private static String decide(ParseContext ctx) {
        var optType = ctx.options.type();
        if (optType != null) return optType;
        if (anyNamed(ctx, "episode") || anyNamed(ctx, "season")
                || anyNamed(ctx, "episode_details") || anyNamed(ctx, "absolute_episode")) {
            return "episode";
        }
        if (anyNamed(ctx, "film")) return "movie";
        var hasYear = anyNamed(ctx, "year");
        if (anyNamed(ctx, "date") && !hasYear) return "episode";
        if (anyNamed(ctx, "bonus") && !hasYear) return "episode";
        var hasCrc = anyNamed(ctx, "crc32");
        var anyAnimeRg = ctx.matches.named("release_group").anyMatch(m -> m.tags().contains("anime"));
        if (hasCrc && anyAnimeRg) return "episode";
        return "movie";
    }

    private static boolean anyNamed(ParseContext ctx, String name) {
        return ctx.matches.named(name).findAny().isPresent();
    }
}
