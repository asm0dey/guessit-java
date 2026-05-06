package io.guessit.engine;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.guessit.engine.MatchName.*;
import static io.guessit.engine.PatternMatcher.regex;
import static io.guessit.engine.PatternMatcher.string;
import static io.guessit.engine.RegexOpts.defaults;
import static io.guessit.engine.Validators.sepsSurround;
import static java.util.Set.of;
import static java.util.regex.Pattern.compile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternMatcherTest {

    @Test
    void regexFindsAllMatches() {
        var p = compile("\\b(?<value>\\d{4})\\b");
        var matches = regex("Movie 1999 Sequel 2020", p, YEAR, defaults());
        Assertions.assertThat(matches).hasSize(2);
        assertThat(matches.getFirst().raw()).isEqualTo("1999");
        assertThat(matches.getFirst().value()).isEqualTo("1999");
    }

    @Test
    void regexValueExtractorParsesInt() {
        var p = compile("\\b(?<value>\\d{4})\\b");
        var opts = defaults().withValue(Integer::parseInt);
        var matches = regex("year 2020", p, YEAR, opts);
        Assertions.assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().value()).isEqualTo(2020);
    }

    @Test
    void regexNamedValueGroupOptional() {
        var p = compile("\\bBluRay\\b");
        var matches = regex("ALPHA.BluRay.x264", p, SOURCE, defaults());
        Assertions.assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().raw()).isEqualTo("BluRay");
        assertThat(matches.getFirst().value()).isEqualTo("BluRay");
    }

    @Test
    void stringMatchesNeedles() {
        var matches = string("Foo.AAC.x264.AAC.mkv", of("AAC"), AUDIO_CODEC, StringOpts.defaults());
        Assertions.assertThat(matches).hasSize(2);
        assertThat(matches.stream().map(Match::start).toList()).isEqualTo(List.of(4, 13));
    }

    @Test
    void stringWholeWord() {
        var matches = string("hauac.AAC.mkv", of("AAC"), OTHER, StringOpts.defaults());
        Assertions.assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().start()).isEqualTo(6);
    }

    @Test
    void stringCaseSensitive() {
        var matches = string("Foo.aac.AAC.mkv", of("AAC"), OTHER,
                StringOpts.defaults().caseSensitive(true));
        Assertions.assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().start()).isEqualTo(8);
    }

    @Test void regex_validatorRejectsMatch() {
        var input = "abc1080xyz";
        var p = java.util.regex.Pattern.compile("\\d{3,4}");
        var opts = RegexOpts.defaults().withValidator(Validators.sepsSurround(input));
        var matches = PatternMatcher.regex(input, p, SCREEN_SIZE, opts);
        assertTrue(matches.isEmpty(), "should reject — no separators surround");
    }

    @Test void regex_validatorAcceptsMatch() {
        var input = "abc.1080.xyz";
        var p = compile("\\d{3,4}");
        var opts = defaults().withValidator(sepsSurround(input));
        var matches = regex(input, p, SCREEN_SIZE, opts);
        Assertions.assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().raw()).isEqualTo("1080");
    }

    @Test void string_validatorRejectsMatch() {
        var input = "abcMP3xyz";
        var opts = StringOpts.defaults().withValidator(Validators.sepsSurround(input));
        var matches = PatternMatcher.string(input, Set.of("MP3"), AUDIO_CODEC, opts);
        Assertions.assertThat(matches).isEmpty();
    }
}
