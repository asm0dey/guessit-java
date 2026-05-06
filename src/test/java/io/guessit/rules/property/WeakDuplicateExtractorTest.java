package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.OptionsBuilder.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeakDuplicateExtractorTest {
    @Test void weakSeasonEpisodeFromFourDigits() {
        // Show.0102.mkv → season=1, episode=2 (weak-duplicate fallback).
        var r = parse("Show.0102.HDTV.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }
    @Test void preferNumberOverridesWeakDuplicate() {
        var r = parse("Show.0102.HDTV.mkv",
                options().episodePreferNumber(true).build());
        assertThat(r.episode()).isEqualTo(102);
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
        var r = parse("Show.S01E02.0304.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }
}
