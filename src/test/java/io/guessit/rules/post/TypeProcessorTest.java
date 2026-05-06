package io.guessit.rules.post;

import io.guessit.Options;
import io.guessit.OptionsBuilder;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeProcessorTest {
    private static ParseContext ctx(String input) {
        return new ParseContext(input, Options.defaults(), OptionsConfig.empty());
    }

    @Test void seasonOrEpisodeYieldsEpisode() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of(MatchName.SEASON, 1, 0, 2, "S1"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named(MatchName.TYPE).findFirst().orElseThrow().value()).isEqualTo("episode");
    }
    @Test void noEpisodeOrYearYieldsMovie() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named(MatchName.TYPE).findFirst().orElseThrow().value()).isEqualTo("movie");
    }
    @Test void dateWithoutYearYieldsEpisode() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of(MatchName.DATE, null, 0, 10, "2020-01-01"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named(MatchName.TYPE).findFirst().orElseThrow().value()).isEqualTo("episode");
    }
    @Test void optionsTypeOverridesEverything() {
        var opts = OptionsBuilder.options().type("movie").build();
        var ctx = new ParseContext("x", opts, OptionsConfig.empty());
        ctx.matches.add(Match.of(MatchName.EPISODE, 1, 0, 1, "1"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named(MatchName.TYPE).findFirst().orElseThrow().value()).isEqualTo("movie");
    }
    @Test void movieTypeRenamesEpisodeTitleToAlternativeTitle() {
        var ctx = ctx("Some Movie");
        ctx.matches.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        ctx.matches.add(Match.of(MatchName.EPISODE_TITLE, "Bonus", 5, 10, "Bonus"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named(MatchName.EPISODE_TITLE).count()).isZero();
        assertThat(ctx.matches.named(MatchName.ALTERNATIVE_TITLE).findFirst().orElseThrow().value()).isEqualTo("Bonus");
    }
    @Test void episodeTitleWithAlternativeReplacedTagSurvivesMovieDemotion() {
        var ctx = ctx("Some Movie");
        ctx.matches.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        ctx.matches.add(new Match(MatchName.EPISODE_TITLE, "X", 5, 6, "X", 1000,
            java.util.Set.of("alternative-replaced"), false));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named(MatchName.EPISODE_TITLE).count()).isOne();
    }
}
