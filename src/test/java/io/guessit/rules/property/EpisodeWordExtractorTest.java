package io.guessit.rules.property;

import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.OptionsBuilder.options;
import static org.assertj.core.api.Assertions.assertThat;

class EpisodeWordExtractorTest {
    @Test void episodeWord() {
        var r = parse("Show Episode 4.mkv");
        assertThat(r.episode()).isEqualTo(4);
    }
    @Test void episodeAbbreviation() {
        var r = parse("Show ep 112.mkv");
        assertThat(r.episode()).isEqualTo(112);
    }
    @Test void seasonWord() {
        var r = parse("Show Season 2.mkv");
        assertThat(r.season()).isEqualTo(2);
    }
    @Test void seasonRomanNumeralEpisodeType() {
        var r = parse("Show Season III.mkv", options().type("episode").build());
        assertThat(r.season()).isEqualTo(3);
    }
    @Test void countDetached() {
        // "Show 4 of 12 mkv" → episode=4, episode_count=12.
        var r = parse("Show 4 of 12.mkv");
        assertThat(r.episode()).isEqualTo(4);
        assertThat(r.episodeCount()).isEqualTo(12);
    }
}
