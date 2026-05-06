package io.guessit.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;

class OptionsConfigTest {

    @Test
    void testTopLevelListReturnsEmptyWhenKeyMissing() {
        var config = new OptionsConfig(Map.of("other", List.of("some")));
        Assertions.assertThat(config.topLevelList("website")).isEmpty();
    }

    @Test
    void testTopLevelListReturnsValues() {
        var config = new OptionsConfig(of("website", List.of("example.com", "test.org")));
        assertThat(config.topLevelList("website")).isEqualTo(List.of("example.com", "test.org"));
    }

    @Test
    void testTopLevelListReturnsEmptyWhenValueIsNotAList() {
        var config = new OptionsConfig(Map.of("website", "string"));
        Assertions.assertThat(config.topLevelList("website")).isEmpty();
    }
}
