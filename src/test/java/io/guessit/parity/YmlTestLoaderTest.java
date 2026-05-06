package io.guessit.parity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YmlTestLoaderTest {

    @Test
    void loadsBasicCase() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/basic.yml");
        assertThat(cases).hasSize(1);
        var c = cases.getFirst();
        assertThat(c.input()).isEqualTo("Movie.Name.2020.1080p.mkv");
        assertThat(c.expected())
                .containsEntry("year", 2020)
                .containsEntry("title", "Movie Name")
                .containsEntry("screen_size", "1080p");
        assertThat(c.negative()).isFalse();
    }

    @Test
    void loadsNegativeCase() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/negative.yml");
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).negative()).isTrue();
        assertThat(cases.get(0).input()).isEqualTo("Title XViD 720p Only");
        assertThat(cases.get(1).negative()).isFalse();
        assertThat(cases.get(1).input()).isEqualTo("Title Only");
        assertThat(cases.get(1).expected()).isEqualTo(cases.get(0).expected());
    }

    @Test
    void loadsCaseWithOptions() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/options.yml");
        assertThat(cases).hasSize(1);
        var c = cases.getFirst();
        assertThat(c.options()).isNotNull();
        assertThat(c.options().episodePreferNumber()).isEqualTo(Boolean.TRUE);
        assertThat(c.expected().containsKey("options")).isFalse();
        assertThat(c.expected()).containsEntry("season", 1);
    }

    @Test
    void loadsEmptyExpected() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/empty.yml");
        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().expected()).isEmpty();
    }

    @Test
    void discoverAllFindsAtLeastOneRealFixture() {
        var all = YmlTestLoader.discoverAll("yml/");
        assertThat(all.isEmpty()).as("should discover real guessit YML cases").isFalse();
    }
}
