package io.guessit.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

class OptionsConfigTest {

    @Test
    void testTopLevelListReturnsEmptyWhenKeyMissing() {
        var config = new OptionsConfig(Map.of("other", List.of("some")));
        assertTrue(config.topLevelList("website").isEmpty());
    }

    @Test
    void testTopLevelListReturnsValues() {
        var config = new OptionsConfig(Map.of("website", List.of("example.com", "test.org")));
        assertEquals(List.of("example.com", "test.org"), config.topLevelList("website"));
    }

    @Test
    void testTopLevelListReturnsEmptyWhenValueIsNotAList() {
        var config = new OptionsConfig(Map.of("website", "string"));
        assertTrue(config.topLevelList("website").isEmpty());
    }
}
