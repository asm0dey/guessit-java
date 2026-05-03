package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeakDuplicateExtractorTest {
    @Test void weakSeasonEpisodeFromFourDigits() {
        // Show.0102.mkv → season=1, episode=2 (weak-duplicate fallback).
        var r = Guessit.parse("Show.0102.HDTV.mkv");
        var map = r.toMap();
        assertEquals(1, map.get("season"));
        assertEquals(2, map.get("episode"));
    }
    @Test void preferNumberOverridesWeakDuplicate() {
        var r = Guessit.parse("Show.0102.HDTV.mkv",
            Options.builder().episodePreferNumber(true).build()).toMap();
        assertEquals(102, r.get("episode"));
        assertNull(r.get("season"));
    }
    @Test void droppedWhenMovie() {
        var r = Guessit.parse("Movie.0102.HDTV.mkv",
            Options.builder().type("movie").build()).toMap();
        assertNull(r.get("season"));
        assertNull(r.get("episode"));
    }
    @Test void droppedWhenStrongSxxExxPresent() {
        // S01E02 is strong → 0304 weak-duplicate is dropped.
        var r = Guessit.parse("Show.S01E02.0304.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
}
