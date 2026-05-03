package io.guessit.config;

import io.guessit.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void loadsBundledDefaults() {
        var cfg = ConfigLoader.load(Options.defaults());
        assertNotNull(cfg);
        assertFalse(cfg.raw().isEmpty(), "bundled config must not be empty");
    }

    @Test
    void noDefaultConfigSkipsBundle() {
        var opts = Options.builder().noDefaultConfig(true).noUserConfig(true).build();
        var cfg = ConfigLoader.load(opts);
        assertTrue(cfg.raw().isEmpty());
    }

    @Test
    void explicitConfigOverridesScalars(@TempDir Path tmp) throws IOException {
        var f = tmp.resolve("override.json");
        Files.writeString(f, "{\"some_key\": \"override\"}");
        var opts = Options.builder()
            .noDefaultConfig(true)
            .noUserConfig(true)
            .configPaths(java.util.List.of(f))
            .build();
        var cfg = ConfigLoader.load(opts);
        assertEquals("override", cfg.raw().get("some_key"));
    }

    @Test
    void mergeListsConcatenates(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        Files.writeString(a, "{\"items\": [1, 2]}");
        Files.writeString(b, "{\"items\": [3]}");
        var opts = Options.builder()
            .noDefaultConfig(true)
            .noUserConfig(true)
            .configPaths(java.util.List.of(a, b))
            .build();
        var cfg = ConfigLoader.load(opts);
        assertEquals(java.util.List.of(1, 2, 3), cfg.raw().get("items"));
    }

    @Test
    void mergeMapsDeep(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        Files.writeString(a, "{\"nested\": {\"x\": 1, \"y\": 2}}");
        Files.writeString(b, "{\"nested\": {\"y\": 3, \"z\": 4}}");
        var opts = Options.builder()
            .noDefaultConfig(true).noUserConfig(true)
            .configPaths(java.util.List.of(a, b)).build();
        var cfg = ConfigLoader.load(opts);
        @SuppressWarnings("unchecked")
        var nested = (java.util.Map<String, Object>) cfg.raw().get("nested");
        assertEquals(1, nested.get("x"));
        assertEquals(3, nested.get("y"));
        assertEquals(4, nested.get("z"));
    }
    
    @Test
    void mergeNullValue(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        Files.writeString(a, "{\"key\": \"original\"}");
        Files.writeString(b, "{\"key\": null}");
        var opts = Options.builder()
            .noDefaultConfig(true).noUserConfig(true)
            .configPaths(java.util.List.of(a, b)).build();
        var cfg = ConfigLoader.load(opts);
        assertNull(cfg.raw().get("key"));
    }
}
