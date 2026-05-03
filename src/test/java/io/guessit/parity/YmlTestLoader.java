package io.guessit.parity;

import io.guessit.Options;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class YmlTestLoader {

    private YmlTestLoader() {}

    public static Stream<YmlCase> discoverAll(String classpathRoot) {
        var loader = Thread.currentThread().getContextClassLoader();
        try {
            var url = loader.getResource(classpathRoot);
            if (url == null) return Stream.empty();
            var rootPath = Path.of(url.toURI());
            return Files.walk(rootPath)
                .filter(p -> {
                    var n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".yml") || n.endsWith(".yaml");
                })
                .flatMap(p -> {
                    var rel = rootPath.getParent().relativize(p).toString();
                    return loadResource(rel).stream();
                });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static List<YmlCase> loadResource(String classpathPath) {
        var loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(classpathPath)) {
            if (in == null) throw new IllegalArgumentException("Resource not found: " + classpathPath);
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                var content = br.lines().reduce("", (a, b) -> a + b + "\n");
                return parseContent(content, classpathPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<YmlCase> parseContent(String content, String fileLabel) {
        var loaderOpts = new LoaderOptions();
        loaderOpts.setMaxAliasesForCollections(Integer.MAX_VALUE);
        var yaml = new Yaml(new SafeConstructor(loaderOpts));

        var topLevel = (Map<Object, Object>) yaml.load(content);
        if (topLevel == null) return List.of();

        var lineByKey = computeLineMap(content, topLevel.keySet());

        var out = new ArrayList<YmlCase>();
        Map<String, Object> defaults = Map.of();
        if (topLevel.containsKey("__default__")) {
            var d = topLevel.get("__default__");
            if (d instanceof Map<?, ?> dm) defaults = (Map<String, Object>) dm;
            topLevel.remove("__default__");
        }

        var pending = new ArrayList<Object>();
        for (var entry : topLevel.entrySet()) {
            var k = entry.getKey();
            var v = entry.getValue();
            pending.add(k);
            if (v != null) {
                Map<String, Object> expected = new LinkedHashMap<>(defaults);
                if (v instanceof Map<?, ?> vm) {
                    expected.putAll((Map<String, Object>) vm);
                }
                Options options = extractOptions(expected);
                expected.remove("options");

                for (var input : pending) {
                    var raw = input.toString();
                    boolean negative = raw.startsWith("-");
                    var cleaned = (negative ? raw.substring(1) : raw).replaceAll("^\\++", "");
                    var line = lineByKey.getOrDefault(input, 0);
                    out.add(new YmlCase(fileLabel, line, cleaned, expected, options, negative));
                }
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            for (var input : pending) {
                var raw = input.toString();
                boolean negative = raw.startsWith("-");
                var cleaned = (negative ? raw.substring(1) : raw).replaceAll("^\\++", "");
                var line = lineByKey.getOrDefault(input, 0);
                out.add(new YmlCase(fileLabel, line, cleaned, Map.of(), Options.defaults(), negative));
            }
        }
        return out;
    }

    /**
     * Mutable accumulator for building Options across multiple option-string / option-map fragments.
     * The Jilt-generated OptionsBuilder only accepts whole lists in setters, so we buffer here
     * then materialize the Options once at the end.
     */
    private static final class OptionsAccum {
        String type, name;
        Boolean dateYearFirst, dateDayFirst, episodePreferNumber, enforceListWhenSingle;
        boolean noUserConfig, noDefaultConfig;
        final List<String> expectedTitle = new ArrayList<>();
        final List<String> expectedGroup = new ArrayList<>();
        final List<String> excludes = new ArrayList<>();
        final List<String> includes = new ArrayList<>();
        final List<String> allowedLanguages = new ArrayList<>();
        final List<String> allowedCountries = new ArrayList<>();
        final List<Path> configPaths = new ArrayList<>();
        final Map<String, Object> raw = new LinkedHashMap<>();

        Options build() {
            return Options.builder()
                .type(type).name(name)
                .expectedTitle(expectedTitle).expectedGroup(expectedGroup)
                .excludes(excludes).includes(includes)
                .allowedLanguages(allowedLanguages).allowedCountries(allowedCountries)
                .dateYearFirst(dateYearFirst).dateDayFirst(dateDayFirst)
                .episodePreferNumber(episodePreferNumber).enforceListWhenSingle(enforceListWhenSingle)
                .configPaths(configPaths)
                .noUserConfig(noUserConfig).noDefaultConfig(noDefaultConfig)
                .raw(raw)
                .build();
        }
    }

    @SuppressWarnings("unchecked")
    private static Options extractOptions(Map<String, Object> expected) {
        var o = expected.get("options");
        if (o == null) return Options.defaults();
        var acc = new OptionsAccum();
        switch (o) {
            case String s -> applyArgString(acc, s);
            case Map<?, ?> m -> m.forEach((k, v) -> applyKv(acc, k.toString(), v));
            case List<?> l -> {
                for (var item : l) applyArgString(acc, item.toString());
            }
            default -> {
            }
        }
        return acc.build();
    }

    private static void applyArgString(OptionsAccum a, String s) {
        var tokens = s.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "--episode-prefer-number" -> a.episodePreferNumber = true;
                case "--date-year-first", "-Y" -> a.dateYearFirst = true;
                case "--date-day-first", "-D" -> a.dateDayFirst = true;
                case "--no-default-config" -> a.noDefaultConfig = true;
                case "--no-user-config" -> a.noUserConfig = true;
                case "--type", "-t" -> { if (i + 1 < tokens.length) a.type = tokens[++i]; }
                case "--name", "-n" -> { if (i + 1 < tokens.length) a.name = tokens[++i]; }
                case "--expected-title", "-T" -> { if (i + 1 < tokens.length) a.expectedTitle.add(tokens[++i]); }
                case "--expected-group", "-G" -> { if (i + 1 < tokens.length) a.expectedGroup.add(tokens[++i]); }
                case "--allowed-language", "-L" -> { if (i + 1 < tokens.length) a.allowedLanguages.add(tokens[++i]); }
                case "--allowed-country", "-C" -> { if (i + 1 < tokens.length) a.allowedCountries.add(tokens[++i]); }
                case "--excludes" -> { if (i + 1 < tokens.length) a.excludes.add(tokens[++i]); }
                case "--includes" -> { if (i + 1 < tokens.length) a.includes.add(tokens[++i]); }
                default -> { /* ignore unknown for now */ }
            }
        }
    }

    private static void applyKv(OptionsAccum a, String k, Object v) {
        switch (k) {
            case "type" -> a.type = v.toString();
            case "name" -> a.name = v.toString();
            case "expected_title" -> { if (v instanceof List<?> l) l.forEach(x -> a.expectedTitle.add(x.toString())); else a.expectedTitle.add(v.toString()); }
            case "expected_group" -> { if (v instanceof List<?> l) l.forEach(x -> a.expectedGroup.add(x.toString())); else a.expectedGroup.add(v.toString()); }
            case "allowed_languages" -> { if (v instanceof List<?> l) l.forEach(x -> a.allowedLanguages.add(x.toString())); }
            case "allowed_countries" -> { if (v instanceof List<?> l) l.forEach(x -> a.allowedCountries.add(x.toString())); }
            case "date_year_first" -> a.dateYearFirst = toBool(v);
            case "date_day_first" -> a.dateDayFirst = toBool(v);
            case "episode_prefer_number" -> a.episodePreferNumber = toBool(v);
            default -> a.raw.put(k, v);
        }
    }

    private static Boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Map<Object, Integer> computeLineMap(String content, java.util.Set<Object> keys) {
        var map = new java.util.HashMap<Object, Integer>();
        var lines = content.split("\n", -1);
        var remaining = new java.util.HashSet<>(keys);
        for (int i = 0; i < lines.length; i++) {
            var trimmed = lines[i].trim();
            if (!trimmed.startsWith("?")) continue;
            var literal = trimmed.substring(1).trim().replaceAll("^\\++", "");
            for (var k : new ArrayList<>(remaining)) {
                if (stripPlus(k.toString()).equals(literal)) {
                    map.put(k, i + 1);
                    remaining.remove(k);
                    break;
                }
            }
        }
        return map;
    }

    private static String stripPlus(String s) {
        return s.replaceAll("^\\++", "");
    }
}
