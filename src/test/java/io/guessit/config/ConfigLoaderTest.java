package io.guessit.config;

import io.guessit.Options;
import io.guessit.OptionsBuilder;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.guessit.OptionsBuilder.options;
import static io.guessit.config.ConfigLoader.load;
import static java.nio.file.Files.writeString;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
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
        var opts = OptionsBuilder.options().noDefaultConfig(true).noUserConfig(true).build();
        var cfg = ConfigLoader.load(opts);
        Assertions.assertThat(cfg.raw()).isEmpty();
    }

    @Test
    void explicitConfigOverridesScalars(@TempDir Path tmp) throws IOException {
        var f = tmp.resolve("override.json");
        writeString(f, "{\"some_key\": \"override\"}");
        var opts = options()
                .noDefaultConfig(true)
                .noUserConfig(true)
                .configPaths(of(f))
                .build();
        var cfg = load(opts);
        assertThat(cfg.raw()).containsEntry("some_key", "override");
    }

    @Test
    void mergeListsConcatenates(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        writeString(a, "{\"items\": [1, 2]}");
        writeString(b, "{\"items\": [3]}");
        var opts = options()
                .noDefaultConfig(true)
                .noUserConfig(true)
                .configPaths(of(a, b))
                .build();
        var cfg = load(opts);
        assertThat(cfg.raw()).containsEntry("items", of(1, 2, 3));
    }

    @Test
    void mergeMapsDeep(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        writeString(a, "{\"nested\": {\"x\": 1, \"y\": 2}}");
        writeString(b, "{\"nested\": {\"y\": 3, \"z\": 4}}");
        var opts = options()
                .noDefaultConfig(true).noUserConfig(true)
                .configPaths(of(a, b)).build();
        var cfg = load(opts);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) cfg.raw().get("nested");
        Assertions.assertThat(nested)
                .containsEntry("x", 1)
                .containsEntry("y", 3)
                .containsEntry("z", 4);
    }
    
    @Test
    void mergeNullValue(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        Files.writeString(a, "{\"key\": \"original\"}");
        Files.writeString(b, "{\"key\": null}");
        var opts = OptionsBuilder.options()
            .noDefaultConfig(true).noUserConfig(true)
            .configPaths(java.util.List.of(a, b)).build();
        var cfg = ConfigLoader.load(opts);
        assertNull(cfg.raw().get("key"));
    }
}
