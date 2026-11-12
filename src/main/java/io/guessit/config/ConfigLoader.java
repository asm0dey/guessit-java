package io.guessit.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.guessit.Options;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class ConfigLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXT_JSON = ".json";
    private static final String EXT_YML = ".yml";
    private static final String EXT_YAML = ".yaml";

    private ConfigLoader() {
    }

    public static class ConfigParseException extends RuntimeException {
        public ConfigParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface ConfigParser {
        Map<String, Object> parse(String content);
    }

    public static OptionsConfig load(Options options) {
        var merged = Stream.of(
                        options.noDefaultConfig() ? Stream.<Map<String, Object>>empty() : Stream.of(readBundled()),
                        options.noUserConfig() ? Stream.<Map<String, Object>>empty() : userConfigPaths().filter(Files::isReadable).map(ConfigLoader::readFile),
                        options.configPaths().stream().filter(Files::isReadable).map(ConfigLoader::readFile),
                        options.raw().isEmpty() ? Stream.<Map<String, Object>>empty() : Stream.of(options.raw())
                )
                .flatMap(s -> s)
                .reduce(new LinkedHashMap<>(), ConfigLoader::deepMerge);

        return new OptionsConfig(merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBundled() {
        try (var in = ConfigLoader.class.getResourceAsStream("/io/guessit/config/options.json")) {
            return in == null ? Map.of() : JSON.readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled options.json", e);
        }
    }

    private static Map<String, Object> readFile(Path p) {
        try {
            var content = Files.readString(p, StandardCharsets.UTF_8);
            var name = p.getFileName().toString().toLowerCase(Locale.ROOT);

            if (name.endsWith(EXT_JSON)) return tryParse(content, ConfigLoader::parseJson);
            if (name.endsWith(EXT_YML) || name.endsWith(EXT_YAML)) return tryParse(content, ConfigLoader::parseYaml);

            return Stream.<ConfigParser>of(ConfigLoader::parseJson, ConfigLoader::parseYaml)
                    .map(parser -> tryParse(content, parser))
                    .filter(map -> !map.isEmpty())
                    .findFirst()
                    .orElse(Map.of());

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config: " + p, e);
        }
    }

    private static Map<String, Object> tryParse(String content, ConfigParser parser) {
        try {
            return parser.parse(content);
        } catch (ConfigParseException _) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String content) {
        try {
            return JSON.readValue(content, Map.class);
        } catch (JsonProcessingException e) {
            throw new ConfigParseException("JSON parsing failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String content) {
        try {
            Object v = new Yaml().load(content);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (RuntimeException e) {
            throw new ConfigParseException("YAML parsing failed", e);
        }
    }

    private static Stream<Path> userConfigPaths() {
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var home = System.getProperty("user.home");
        var xdgBase = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".config");

        return Stream.of(EXT_JSON, EXT_YML, EXT_YAML)
                .flatMap(ext -> Stream.of(
                        xdgBase.resolve("guessit").resolve("options" + ext),
                        Path.of(home, ".guessit", "options" + ext)
                ));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        var out = new LinkedHashMap<>(base);

        overlay.forEach((k, v) -> {
            switch (out.get(k)) {
                case Map<?, ?> em when v instanceof Map<?, ?> vm ->
                        out.put(k, deepMerge((Map<String, Object>) em, (Map<String, Object>) vm));

                case List<?> el when v instanceof List<?> vl ->
                        out.put(k, Stream.concat(el.stream(), vl.stream()).toList());

                case null, default ->
                        out.put(k, v);
            }
        });

        return out;
    }
}