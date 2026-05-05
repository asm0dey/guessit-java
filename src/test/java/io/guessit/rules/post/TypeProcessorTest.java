package io.guessit.rules.post;

import io.guessit.Options;
import io.guessit.OptionsBuilder;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeProcessorTest {
    private static ParseContext ctx(String input) {
        return new ParseContext(input, Options.defaults(), OptionsConfig.empty());
    }

    @Test void seasonOrEpisodeYieldsEpisode() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of("season", 1, 0, 2, "S1"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("episode");
    }
    @Test void noEpisodeOrYearYieldsMovie() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of("year", 2020, 0, 4, "2020"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("movie");
    }
    @Test void dateWithoutYearYieldsEpisode() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of("date", null, 0, 10, "2020-01-01"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("episode");
    }
    @Test void optionsTypeOverridesEverything() {
        var opts = OptionsBuilder.options().type("movie").build();
        var ctx = new ParseContext("x", opts, OptionsConfig.empty());
        ctx.matches.add(Match.of("episode", 1, 0, 1, "1"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("movie");
    }
    @Test void movieTypeRenamesEpisodeTitleToAlternativeTitle() {
        var ctx = ctx("Some Movie");
        ctx.matches.add(Match.of("year", 2020, 0, 4, "2020"));
        ctx.matches.add(Match.of("episode_title", "Bonus", 5, 10, "Bonus"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("episode_title").count()).isZero();
        assertThat(ctx.matches.named("alternative_title").findFirst().orElseThrow().value()).isEqualTo("Bonus");
    }
    @Test void episodeTitleWithAlternativeReplacedTagSurvivesMovieDemotion() {
        var ctx = ctx("Some Movie");
        ctx.matches.add(Match.of("year", 2020, 0, 4, "2020"));
        ctx.matches.add(new Match("episode_title", "X", 5, 6, "X", 1000,
            java.util.Set.of("alternative-replaced"), false));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("episode_title").count()).isOne();
    }
}
