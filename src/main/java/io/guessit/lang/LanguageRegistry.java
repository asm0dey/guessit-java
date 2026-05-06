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
    private static final String ALPHA2_KEY = "alpha2";
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
        loadSpecialLangAliases();
        loadLangAliases();
        loadCountryAliases();
    }

    public Optional<Language> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        var key = token.trim().toLowerCase(Locale.ROOT);
        var override = langAliasOverrides.get(key);
        if (override != null) return Optional.of(override);
        var direct = langByKey.get(key);
        if (direct != null) return Optional.of(direct);
        // babelfish-style "lang-COUNTRY" form (e.g. "pt-BR"): resolve language part
        // and attach the country qualifier so equality survives "pt" vs "pt-BR".
        int dash = key.indexOf('-');
        if (dash > 0) {
            var base = langByKey.get(key.substring(0, dash));
            if (base == null) return Optional.empty();
            var country = countryByKey.get(key.substring(dash + 1));
            return country == null ? Optional.of(base)
                    : Optional.of(new Language(base.alpha2(), base.alpha3(), base.name(), country));
        }
        return Optional.empty();
    }

    public Optional<Country> findCountry(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return Optional.ofNullable(countryByKey.get(token.trim().toLowerCase(Locale.ROOT)));
    }

    private void loadIso639() {
        forEachRecord("data/iso-639.csv", r -> {
            var a2 = trimOrEmpty(r, ALPHA2_KEY);
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
            var a2 = trimOrEmpty(r, ALPHA2_KEY);
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

    /**
     * Hardcoded non-ISO aliases that babelfish exposes but the ISO-639 CSV does
     * not encode (or encodes with a different display). These take precedence
     * over the ISO table via the override map.
     */
    private void loadSpecialLangAliases() {
        langAliasOverrides.put("vo",  new Language(null, "vo",  "Original Version"));
        langAliasOverrides.put("mul", new Language(null, "mul", "Multiple languages"));
        langAliasOverrides.put("und", new Language(null, "und", "Undetermined"));
        langAliasOverrides.put("zxx", new Language(null, "zxx", "No linguistic content"));
    }

    private void loadLangAliases() {
        forEachRecord("data/lang-aliases.csv", r -> {
            var alias = trimOrEmpty(r, "alias");
            var alpha3 = trimOrEmpty(r, "alpha3");
            if (alias.isEmpty()) return;
            String aliasKey = alias.toLowerCase(Locale.ROOT);
            Language resolved = langByKey.get(alpha3.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                var note = trimOrEmpty(r, "note");
                resolved = new Language(null, alpha3.isEmpty() ? null : alpha3,
                        note.isEmpty() ? alias : note);
            }
            langAliasOverrides.put(aliasKey, resolved);
        });
    }

    private void loadCountryAliases() {
        forEachRecord("data/country-aliases.csv", r -> {
            var alias = trimOrEmpty(r, "alias");
            var a2 = trimOrEmpty(r, ALPHA2_KEY);
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
