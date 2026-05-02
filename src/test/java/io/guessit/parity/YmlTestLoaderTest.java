package io.guessit.parity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YmlTestLoaderTest {

    @Test
    void loadsBasicCase() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/basic.yml");
        assertEquals(1, cases.size());
        var c = cases.get(0);
        assertEquals("Movie.Name.2020.1080p.mkv", c.input());
        assertEquals(2020, c.expected().get("year"));
        assertEquals("Movie Name", c.expected().get("title"));
        assertEquals("1080p", c.expected().get("screen_size"));
        assertFalse(c.negative());
    }

    @Test
    void loadsNegativeCase() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/negative.yml");
        assertEquals(2, cases.size());
        assertTrue(cases.get(0).negative());
        assertEquals("Title XViD 720p Only", cases.get(0).input());
        assertFalse(cases.get(1).negative());
        assertEquals("Title Only", cases.get(1).input());
        assertEquals(cases.get(0).expected(), cases.get(1).expected());
    }

    @Test
    void loadsCaseWithOptions() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/options.yml");
        assertEquals(1, cases.size());
        var c = cases.get(0);
        assertNotNull(c.options());
        assertEquals(Boolean.TRUE, c.options().episodePreferNumber());
        assertFalse(c.expected().containsKey("options"));
        assertEquals(1, c.expected().get("season"));
    }

    @Test
    void loadsEmptyExpected() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/empty.yml");
        assertEquals(1, cases.size());
        assertTrue(cases.get(0).expected().isEmpty());
    }

    @Test
    void discoverAllFindsAtLeastOneRealFixture() {
        var all = YmlTestLoader.discoverAll("yml/").toList();
        assertFalse(all.isEmpty(), "should discover real guessit YML cases");
    }
}
