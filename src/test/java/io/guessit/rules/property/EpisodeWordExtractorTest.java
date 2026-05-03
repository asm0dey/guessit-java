package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeWordExtractorTest {
    @Test void episodeWord() {
        var r = Guessit.parse("Show Episode 4.mkv").toMap();
        assertEquals(4, r.get("episode"));
    }
    @Test void episodeAbbreviation() {
        var r = Guessit.parse("Show ep 112.mkv").toMap();
        assertEquals(112, r.get("episode"));
    }
    @Test void seasonWord() {
        var r = Guessit.parse("Show Season 2.mkv").toMap();
        assertEquals(2, r.get("season"));
    }
    @Test void seasonRomanNumeralEpisodeType() {
        var r = Guessit.parse("Show Season III.mkv", Options.builder().type("episode").build()).toMap();
        assertEquals(3, r.get("season"));
    }
    @Test void countDetached() {
        // "Show 4 of 12 mkv" → episode=4, episode_count=12.
        var r = Guessit.parse("Show 4 of 12.mkv").toMap();
        assertEquals(4, r.get("episode"));
        assertEquals(12, r.get("episode_count"));
    }
}
