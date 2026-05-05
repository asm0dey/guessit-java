package io.guessit.rules.post;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.Marker;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnlargeGroupMatchesTest {

    private static ParseContext ctx(String input) {
        return new ParseContext(input, Options.defaults(), OptionsConfig.empty());
    }

    @Test
    void matchTouchingGroupStartIsExtendedLeft() {
        // input: "[abc value]"  positions: 0=[, 1=a..c, 5=v..e, 10=]
        // group marker: start=0, end=11
        // match: start=1, end=4  — touches left boundary only (end=4 != group.end-1=10)
        // expected after processing: start=0, end=4
        var ctx = ctx("[abc value]");
        ctx.markers.add(new Marker("group", 0, 11, "[abc value]"));
        ctx.matches.add(Match.of("title", "abc", 1, 4, "abc"));

        new EnlargeGroupMatches().process(ctx);

        var result = ctx.matches.named("title").findFirst().orElseThrow();
        assertThat(result.start()).isZero();
        assertThat(result.end()).isEqualTo(4);
    }

    @Test
    void matchTouchingGroupEndIsExtendedRight() {
        // input: "[abc value]"  positions: 0=[, 5=v..u..e, 10=]
        // group marker: start=0, end=11
        // match: start=5, end=10 — touches right boundary only (start=5 != group.start+1=1)
        // expected after processing: start=5, end=11
        var ctx = ctx("[abc value]");
        ctx.markers.add(new Marker("group", 0, 11, "[abc value]"));
        ctx.matches.add(Match.of("title", "value", 5, 10, "value"));

        new EnlargeGroupMatches().process(ctx);

        var result = ctx.matches.named("title").findFirst().orElseThrow();
        assertThat(result.start()).isEqualTo(5);
        assertThat(result.end()).isEqualTo(11);
    }

    @Test
    void matchTouchingBothBoundariesIsExtendedBothWays() {
        // input: "[value]"  positions: 0=[, 1..5=value, 6=]
        // group marker: start=0, end=7
        // match: start=1, end=6 — touches both boundaries
        // expected after processing: start=0, end=7
        var ctx = ctx("[value]");
        ctx.markers.add(new Marker("group", 0, 7, "[value]"));
        ctx.matches.add(Match.of("title", "value", 1, 6, "value"));

        new EnlargeGroupMatches().process(ctx);

        var result = ctx.matches.named("title").findFirst().orElseThrow();
        assertThat(result.start()).isZero();
        assertThat(result.end()).isEqualTo(7);
    }

    @Test
    void nonGroupMarkerIsIgnored() {
        // "path" markers should not trigger enlargement
        var ctx = ctx("[value]");
        ctx.markers.add(new Marker("path", 0, 7, "[value]"));
        ctx.matches.add(Match.of("title", "value", 1, 6, "value"));

        new EnlargeGroupMatches().process(ctx);

        var result = ctx.matches.named("title").findFirst().orElseThrow();
        assertThat(result.start()).isEqualTo(1);
        assertThat(result.end()).isEqualTo(6);
    }

    @Test
    void matchNotTouchingBoundariesIsUnchanged() {
        // match entirely inside the group but not at boundaries
        var ctx = ctx("[abc value xyz]");
        ctx.markers.add(new Marker("group", 0, 15, "[abc value xyz]"));
        ctx.matches.add(Match.of("title", "value", 5, 10, "value"));

        new EnlargeGroupMatches().process(ctx);

        var result = ctx.matches.named("title").findFirst().orElseThrow();
        assertThat(result.start()).isEqualTo(5);
        assertThat(result.end()).isEqualTo(10);
    }
}
