package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeakDuplicateExtractorTest {
    @Test void weakSeasonEpisodeFromFourDigits() {
        // Show.0102.mkv → season=1, episode=2 (weak-duplicate fallback).
        var r = Guessit.parse("Show.0102.HDTV.mkv");
        assertEquals(1, r.season());
        assertEquals(2, r.episode());
    }
    @Test void preferNumberOverridesWeakDuplicate() {
        var r = Guessit.parse("Show.0102.HDTV.mkv",
            OptionsBuilder.options().episodePreferNumber(true).build());
        assertEquals(102, r.episode());
        assertNull(r.season());
    }
    @Test void droppedWhenMovie() {
        var r = Guessit.parse("Movie.0102.HDTV.mkv",
            OptionsBuilder.options().type("movie").build());
        assertNull(r.season());
        assertNull(r.episode());
    }
    @Test void droppedWhenStrongSxxExxPresent() {
        // S01E02 is strong → 0304 weak-duplicate is dropped.
        var r = Guessit.parse("Show.S01E02.0304.mkv");
        assertEquals(1, r.season());
        assertEquals(2, r.episode());
    }
}
