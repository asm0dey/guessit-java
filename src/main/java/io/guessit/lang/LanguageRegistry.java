package io.guessit.lang;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class LanguageRegistry {
    private static final LanguageRegistry INSTANCE = new LanguageRegistry();
    public static LanguageRegistry instance() { return INSTANCE; }

    private final Map<String, Language> langByKey = new HashMap<>();
    private final Map<String, Country> countryByKey = new HashMap<>();
    private final Map<String, String> scripts = new HashMap<>();

    // Aliases that produce non-ISO Languages (e.g. "vo" -> Original Version)
    private final Map<String, Language> langAliasOverrides = new HashMap<>();

    private LanguageRegistry() {
        loadIso639();
        loadIso3166();
        loadScripts();
        loadLangAliases();
        loadCountryAliases();
    }

    public Optional<Language> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        var key = token.trim().toLowerCase(Locale.ROOT);
        var override = langAliasOverrides.get(key);
        if (override != null) return Optional.of(override);
        return Optional.ofNullable(langByKey.get(key));
    }

    public Optional<Country> findCountry(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return Optional.ofNullable(countryByKey.get(token.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<String> findScript(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return Optional.ofNullable(scripts.get(token.trim().toLowerCase(Locale.ROOT)));
    }

    private void loadIso639() {
        forEachRecord("data/iso-639.csv", r -> {
            var a2 = trimOrEmpty(r, "alpha2");
            var a3 = trimOrEmpty(r, "alpha3");
            var name = trimOrEmpty(r, "name");
            if (name.isEmpty()) return;
            var lang = new Language(a2.isEmpty() ? null : a2, a3.isEmpty() ? null : a3, name);
            // alpha2/alpha3 codes are authoritative; use put() so they override any prior name-collision
            if (!a2.isEmpty()) langByKey.put(a2.toLowerCase(Locale.ROOT), lang);
            if (!a3.isEmpty()) langByKey.put(a3.toLowerCase(Locale.ROOT), lang);
            langByKey.putIfAbsent(name.toLowerCase(Locale.ROOT), lang);
        });
    }

    private void loadIso3166() {
        forEachRecord("data/iso-3166-1.csv", r -> {
            var a2 = trimOrEmpty(r, "alpha2");
            var rawName = trimOrEmpty(r, "name");
            if (a2.isEmpty() || rawName.isEmpty()) return;
            var name = titleCase(rawName);
            var c = new Country(a2.toUpperCase(Locale.ROOT), name);
            countryByKey.putIfAbsent(a2.toLowerCase(Locale.ROOT), c);
            countryByKey.putIfAbsent(name.toLowerCase(Locale.ROOT), c);
            // also key by raw lowercase form (e.g. "united states") so case-insensitive name lookup
            // works whether caller types "United States" or "united states"
            countryByKey.putIfAbsent(rawName.toLowerCase(Locale.ROOT), c);
        });
    }

    private void loadScripts() {
        forEachRecord("data/scripts.csv", r -> {
            var code = trimOrEmpty(r, "code");
            var name = trimOrEmpty(r, "name");
            if (code.isEmpty() || name.isEmpty()) return;
            scripts.putIfAbsent(code.toLowerCase(Locale.ROOT), name);
        });
    }

    private void loadLangAliases() {
        // TODO(plan-2): some aliases (pob, swissgerman, flemish) imply a country qualifier in the note field;
        // not yet propagated to a Language+Country pair.
        forEachRecord("data/lang-aliases.csv", r -> {
            var alias = trimOrEmpty(r, "alias");
            var alpha3 = trimOrEmpty(r, "alpha3");
            if (alias.isEmpty()) return;
            // Check for well-known alias overrides that need a custom display name regardless of alpha3
            String aliasKey = alias.toLowerCase(Locale.ROOT);
            Language target = switch (aliasKey) {
                case "vo"  -> new Language(null, alpha3.isEmpty() ? null : alpha3, "Original Version");
                case "mul" -> new Language(null, alpha3.isEmpty() ? null : alpha3, "Multiple languages");
                case "und" -> new Language(null, alpha3.isEmpty() ? null : alpha3, "Undetermined");
                case "zxx" -> new Language(null, alpha3.isEmpty() ? null : alpha3, "No linguistic content");
                default    -> {
                    // Try to resolve from ISO 639 table; fall back to a synthetic Language
                    Language resolved = langByKey.get(alpha3.toLowerCase(Locale.ROOT));
                    if (resolved == null) {
                        var note = trimOrEmpty(r, "note");
                        resolved = new Language(null, alpha3.isEmpty() ? null : alpha3,
                                note.isEmpty() ? alias : note);
                    }
                    yield resolved;
                }
            };
            langAliasOverrides.put(aliasKey, target);
        });
    }

    private void loadCountryAliases() {
        forEachRecord("data/country-aliases.csv", r -> {
            var alias = trimOrEmpty(r, "alias");
            var a2 = trimOrEmpty(r, "alpha2");
            if (alias.isEmpty() || a2.isEmpty()) return;
            var resolved = countryByKey.get(a2.toLowerCase(Locale.ROOT));
            if (resolved != null) countryByKey.putIfAbsent(alias.toLowerCase(Locale.ROOT), resolved);
        });
    }

    private static String trimOrEmpty(CSVRecord r, String header) {
        if (!r.isMapped(header)) return "";
        var v = r.get(header);
        return v == null ? "" : v.trim();
    }

    private static String titleCase(String s) {
        var parts = s.toLowerCase(Locale.ROOT).split(" ");
        var sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            var p = parts[i];
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private void forEachRecord(String resourceName, java.util.function.Consumer<CSVRecord> consumer) {
        var path = "/io/guessit/" + resourceName;
        try (InputStream in = LanguageRegistry.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + path);
            try (var parser = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .setIgnoreSurroundingSpaces(true)
                        .get())) {
                for (var record : parser) consumer.accept(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
