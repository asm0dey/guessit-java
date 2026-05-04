package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternMatcherTest {

    @Test
    void regexFindsAllMatches() {
        var p = Pattern.compile("\\b(?<value>\\d{4})\\b");
        var matches = PatternMatcher.regex("Movie 1999 Sequel 2020", p, "year", RegexOpts.defaults());
        assertEquals(2, matches.size());
        assertEquals("1999", matches.getFirst().raw());
        assertEquals("1999", matches.getFirst().value());
    }

    @Test
    void regexValueExtractorParsesInt() {
        var p = Pattern.compile("\\b(?<value>\\d{4})\\b");
        var opts = RegexOpts.defaults().withValue(Integer::parseInt);
        var matches = PatternMatcher.regex("year 2020", p, "year", opts);
        assertEquals(1, matches.size());
        assertEquals(2020, matches.getFirst().value());
    }

    @Test
    void regexNamedValueGroupOptional() {
        var p = Pattern.compile("\\bBluRay\\b");
        var matches = PatternMatcher.regex("ALPHA.BluRay.x264", p, "source", RegexOpts.defaults());
        assertEquals(1, matches.size());
        assertEquals("BluRay", matches.getFirst().raw());
        assertEquals("BluRay", matches.getFirst().value());
    }

    @Test
    void stringMatchesNeedles() {
        var matches = PatternMatcher.string("Foo.AAC.x264.AAC.mkv", Set.of("AAC"), "audio_codec", StringOpts.defaults());
        assertEquals(2, matches.size());
        assertEquals(List.of(4, 13), matches.stream().map(Match::start).toList());
    }

    @Test
    void stringWholeWord() {
        var matches = PatternMatcher.string("hauac.AAC.mkv", Set.of("AAC"), "x", StringOpts.defaults());
        assertEquals(1, matches.size());
        assertEquals(6, matches.getFirst().start());
    }

    @Test
    void stringCaseSensitive() {
        var matches = PatternMatcher.string("Foo.aac.AAC.mkv", Set.of("AAC"), "x",
            StringOpts.defaults().caseSensitive(true));
        assertEquals(1, matches.size());
        assertEquals(8, matches.getFirst().start());
    }

    @Test void regex_validatorRejectsMatch() {
        var input = "abc1080xyz";
        var p = java.util.regex.Pattern.compile("\\d{3,4}");
        var opts = RegexOpts.defaults().withValidator(Validators.sepsSurround(input));
        var matches = PatternMatcher.regex(input, p, "screen_size", opts);
        assertTrue(matches.isEmpty(), "should reject — no separators surround");
    }

    @Test void regex_validatorAcceptsMatch() {
        var input = "abc.1080.xyz";
        var p = java.util.regex.Pattern.compile("\\d{3,4}");
        var opts = RegexOpts.defaults().withValidator(Validators.sepsSurround(input));
        var matches = PatternMatcher.regex(input, p, "screen_size", opts);
        assertEquals(1, matches.size());
        assertEquals("1080", matches.getFirst().raw());
    }

    @Test void string_validatorRejectsMatch() {
        var input = "abcMP3xyz";
        var opts = StringOpts.defaults().withValidator(Validators.sepsSurround(input));
        var matches = PatternMatcher.string(input, java.util.Set.of("MP3"), "audio_codec", opts);
        assertTrue(matches.isEmpty());
    }
}
