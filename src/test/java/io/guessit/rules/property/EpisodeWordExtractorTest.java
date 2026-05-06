package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeWordExtractorTest {
    @Test void episodeWord() {
        var r = Guessit.parse("Show Episode 4.mkv");
        assertEquals(4, r.episode());
    }
    @Test void episodeAbbreviation() {
        var r = Guessit.parse("Show ep 112.mkv");
        assertEquals(112, r.episode());
    }
    @Test void seasonWord() {
        var r = Guessit.parse("Show Season 2.mkv");
        assertEquals(2, r.season());
    }
    @Test void seasonRomanNumeralEpisodeType() {
        var r = Guessit.parse("Show Season III.mkv", OptionsBuilder.options().type("episode").build());
        assertEquals(3, r.season());
    }
    @Test void countDetached() {
        // "Show 4 of 12 mkv" → episode=4, episode_count=12.
        var r = Guessit.parse("Show 4 of 12.mkv");
        assertEquals(4, r.episode());
        assertEquals(12, r.episodeCount());
    }
}
