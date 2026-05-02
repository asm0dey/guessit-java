package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PatternMatcherTest {

    @Test
    void regexFindsAllMatches() {
        var p = Pattern.compile("\\b(?<value>\\d{4})\\b");
        var matches = PatternMatcher.regex("Movie 1999 Sequel 2020", p, "year", RegexOpts.defaults());
        assertEquals(2, matches.size());
        assertEquals("1999", matches.get(0).raw());
        assertEquals("1999", matches.get(0).value());
    }

    @Test
    void regexValueExtractorParsesInt() {
        var p = Pattern.compile("\\b(?<value>\\d{4})\\b");
        var opts = RegexOpts.defaults().withValue(Integer::parseInt);
        var matches = PatternMatcher.regex("year 2020", p, "year", opts);
        assertEquals(1, matches.size());
        assertEquals(2020, matches.get(0).value());
    }

    @Test
    void regexNamedValueGroupOptional() {
        var p = Pattern.compile("\\bBluRay\\b");
        var matches = PatternMatcher.regex("ALPHA.BluRay.x264", p, "source", RegexOpts.defaults());
        assertEquals(1, matches.size());
        assertEquals("BluRay", matches.get(0).raw());
        assertEquals("BluRay", matches.get(0).value());
    }

    @Test
    void stringMatchesNeedles() {
        var matches = PatternMatcher.string("Foo.AAC.x264.AAC.mkv", Set.of("AAC"), "audio_codec", StringOpts.defaults());
        assertEquals(2, matches.size());
        assertEquals(List.of(4, 13), matches.stream().map(Match::start).collect(Collectors.toList()));
    }

    @Test
    void stringWholeWord() {
        var matches = PatternMatcher.string("hauac.AAC.mkv", Set.of("AAC"), "x", StringOpts.defaults());
        assertEquals(1, matches.size());
        assertEquals(6, matches.get(0).start());
    }

    @Test
    void stringCaseSensitive() {
        var matches = PatternMatcher.string("Foo.aac.AAC.mkv", Set.of("AAC"), "x",
            StringOpts.defaults().caseSensitive(true));
        assertEquals(1, matches.size());
        assertEquals(8, matches.get(0).start());
    }
}
