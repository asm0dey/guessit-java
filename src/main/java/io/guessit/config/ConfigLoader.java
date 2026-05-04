package io.guessit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.guessit.Options;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ConfigLoader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigLoader() {
    }

    public static OptionsConfig load(Options options) {
        Map<String, Object> merged = new LinkedHashMap<>();

        if (!options.noDefaultConfig()) {
            var bundled = readBundled();
            if (bundled != null) merged = deepMerge(merged, bundled);
        }

        if (!options.noUserConfig()) {
            for (var p : userConfigPaths()) {
                var loaded = readFile(p);
                if (loaded != null) merged = deepMerge(merged, loaded);
            }
        }

        for (var p : options.configPaths()) {
            var loaded = readFile(p);
            if (loaded != null) merged = deepMerge(merged, loaded);
        }

        if (!options.raw().isEmpty()) {
            merged = deepMerge(merged, options.raw());
        }

        return new OptionsConfig(merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBundled() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/io/guessit/config/options.json")) {
            if (in == null) return null;
            return JSON.readValue(in, Map.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read bundled options.json", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readFile(Path p) {
        if (!Files.isReadable(p)) return null;
        try {
            var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                if (name.endsWith(".json")) {
                    return JSON.readValue(r, Map.class);
                }
                if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    Object v = new Yaml().load(r);
                    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                }
                var content = Files.readString(p, StandardCharsets.UTF_8);
                return loadConfigFromString(content);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config: " + p, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfigFromString(String content) {
        try {
            return JSON.readValue(content, Map.class);
        } catch (IOException _) {
            Object v = new Yaml().load(content);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
    }

    private static List<Path> userConfigPaths() {
        var paths = new ArrayList<Path>();
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var home = System.getProperty("user.home");
        Path xdgBase = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(home, ".config");
        for (var ext : List.of(".json", ".yml", ".yaml")) {
            paths.add(xdgBase.resolve("guessit").resolve("options" + ext));
        }
        for (var ext : List.of(".json", ".yml", ".yaml")) {
            paths.add(Path.of(home, ".guessit", "options" + ext));
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        var out = new LinkedHashMap<>(base);
        for (var e : overlay.entrySet()) {
            var k = e.getKey();
            var v = e.getValue();
            var existing = out.get(k);
            if (existing instanceof Map<?, ?> em && v instanceof Map<?, ?> vm) {
                out.put(k, deepMerge((Map<String, Object>) em, (Map<String, Object>) vm));
            } else if (existing instanceof List<?> el && v instanceof List<?> vl) {
                var combined = new ArrayList<Object>(el);
                combined.addAll(vl);
                out.put(k, combined);
            } else {
                out.put(k, v);
            }
        }
        return out;
    }
}
