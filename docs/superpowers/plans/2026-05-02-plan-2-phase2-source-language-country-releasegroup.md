# Plan 2: Phase 2 Extractors — source, other, website, streaming_service, language, subtitle_language, country, release_group

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Phase 2 extractors against the foundation laid in Plan 0 and the Phase 1 properties shipped in Plan 1. End state: `Rules.allInOrder()` registers `OtherExtractor`, `SourceExtractor`, `WebsiteExtractor`, `StreamingServiceExtractor`, `LanguageExtractor`, `CountryExtractor`, `ReleaseGroupExtractor`; their per-rule unit tests pass; the YML parity suite gate widens to include `source`, `other`, `language`, `subtitle_language`, `country`, `release_group`, `website`, `streaming_service` plus the Phase 1 props, and ≥50% of all YML cases pass.

**Architecture:** Each extractor lives in `io.guessit.rules.property.<Name>Extractor` and implements `Extractor` from Plan 0. New shared helpers go in `io.guessit.engine`: `Words` (token iterator equivalent to Python `iter_words`) and an additional `OptionsConfig.topLevel(key)` accessor for top-level lists like `allowed_languages`. Per-rule post-processing (e.g. `ValidateSourcePrefixSuffix`, `ValidateWeakSource`, `UltraHdBlurayRule`, `SubtitlePrefixLanguageRule`, `SubtitleSuffixLanguageRule`, `SubtitleExtensionRule`, `RemoveInvalidLanguages`, `RemoveUndeterminedLanguages`, `PreferTitleOverWebsite`, `ValidateStreamingService`, `DashSeparatedReleaseGroup`, `SceneReleaseGroup`, `AnimeReleaseGroup`) runs in `Extractor.postProcess(ctx)` after the central `ConflictSolver`, mirroring Python's rebulk pass order.

**Tech Stack:** Same as Plan 0/1 — Java 25, JUnit Jupiter 5.12.x, Apache Commons CSV (already on classpath), Jackson + SnakeYAML for config, no new dependencies.

**Reference source:**
- Python: `/tmp/guessit/guessit/rules/properties/{source,other,website,streaming_service,language,country,release_group}.py`
- Python helpers: `/tmp/guessit/guessit/rules/common/{__init__.py,validators.py,words.py,formatters.py,comparators.py,expected.py,pattern.py}`
- Python config: `/tmp/guessit/guessit/config/options.json` (top-level `allowed_languages`, `allowed_countries`, `expected_title`; `advanced_config.{language,country,other,source,release_group,website,streaming_service}`)
- Spec: `docs/superpowers/specs/2026-05-02-guessit-java-design.md`
- Plan 0: `docs/superpowers/plans/2026-05-02-plan-0-foundation.md`
- Plan 1: `docs/superpowers/plans/2026-05-02-plan-1-phase1-extractors.md`

---

## File Structure

Created in this plan (paths relative to repo root):

```
src/main/java/io/guessit/
├── engine/
│   └── Words.java                                 // Python iter_words equivalent
└── rules/
    └── property/
        ├── OtherExtractor.java
        ├── SourceExtractor.java
        ├── WebsiteExtractor.java
        ├── StreamingServiceExtractor.java
        ├── LanguageExtractor.java
        ├── CountryExtractor.java
        └── ReleaseGroupExtractor.java

src/test/java/io/guessit/
├── engine/
│   └── WordsTest.java
└── rules/property/
    ├── OtherExtractorTest.java
    ├── SourceExtractorTest.java
    ├── WebsiteExtractorTest.java
    ├── StreamingServiceExtractorTest.java
    ├── LanguageExtractorTest.java
    ├── CountryExtractorTest.java
    └── ReleaseGroupExtractorTest.java
```

Modified in this plan:

```
src/main/java/io/guessit/config/OptionsConfig.java   // add topLevel(key) accessor
src/main/java/io/guessit/rules/Rules.java            // register Phase 2 extractors
src/test/java/io/guessit/parity/YmlParityTest.java   // widen PHASE props gate, raise threshold
```

Responsibilities (one per file):
- `engine/Words` — split a string into runs of letters/digits, returning `(start,end,value)` tokens; mirrors Python `rules/common/words.py` `iter_words`.
- `config/OptionsConfig.topLevel(key)` — read top-level keys (e.g. `allowed_languages`, `allowed_countries`) from the merged config; complements the existing `section(name)` which unwraps `advanced_config`.
- `rules/property/OtherExtractor` — config-driven `other` matches (Proper, 3D, HQ, Hardcoded, ColorDepth-related). Reads `advanced_config.other.other` map.
- `rules/property/SourceExtractor` — main source regex set + the `(?P<other>Rip)` paired matches; runs `ValidateSourcePrefixSuffix`, `ValidateWeakSource`, `UltraHdBlurayRule` in `postProcess`.
- `rules/property/WebsiteExtractor` — TLD-based + safe-TLD regex; `PreferTitleOverWebsite` runs in `postProcess`.
- `rules/property/StreamingServiceExtractor` — config-driven string + regex per service from `advanced_config.streaming_service`; `ValidateStreamingService` runs in `postProcess`.
- `rules/property/LanguageExtractor` — uses `Words` + `LanguageRegistry` to emit `language` and `subtitle_language` matches with affix awareness; `SubtitlePrefixLanguageRule`, `SubtitleSuffixLanguageRule`, `SubtitleExtensionRule`, `RemoveInvalidLanguages`, `RemoveUndeterminedLanguages` run in `postProcess`.
- `rules/property/CountryExtractor` — uses `Words` + `LanguageRegistry.findCountry` filtered by `allowed_countries`.
- `rules/property/ReleaseGroupExtractor` — `expected_group` from `Options`, plus `DashSeparatedReleaseGroup`, simplified `SceneReleaseGroup` (works against trailing hole anchored to filepart end without depending on title), and `AnimeReleaseGroup` (anime-style empty group marker).

---

## Task 1: `engine/Words` word tokenizer

**Files:**
- Create: `src/main/java/io/guessit/engine/Words.java`
- Test: `src/test/java/io/guessit/engine/WordsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordsTest {
    @Test void splitsAlphanumericRuns() {
        var words = Words.iter("Movie.Name.2015.1080p.BluRay-RG");
        assertEquals(
            List.of("Movie", "Name", "2015", "1080p", "BluRay", "RG"),
            words.stream().map(Words.Word::value).toList());
    }
    @Test void offsetsAreCharSpansInOriginalInput() {
        var input = "Foo Bar";
        var words = Words.iter(input);
        assertEquals("Foo", words.get(0).value());
        assertEquals(0, words.get(0).start());
        assertEquals(3, words.get(0).end());
        assertEquals("Bar", words.get(1).value());
        assertEquals(4, words.get(1).start());
        assertEquals(7, words.get(1).end());
    }
    @Test void emptyAndAllSepsReturnEmpty() {
        assertTrue(Words.iter("").isEmpty());
        assertTrue(Words.iter(" . - _ ").isEmpty());
    }
    @Test void underscoresAreSeparators() {
        var values = Words.iter("a_b_c").stream().map(Words.Word::value).toList();
        assertEquals(List.of("a", "b", "c"), values);
    }
    @Test void highCharsCountAsLetters() {
        // Non-ASCII letters must still be treated as letters, matching Python str.isalpha.
        var v = Words.iter("Pelícano").stream().map(Words.Word::value).toList();
        assertEquals(List.of("Pelícano"), v);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=WordsTest`
Expected: FAIL — class `Words` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;

public final class Words {
    private Words() {}

    public record Word(int start, int end, String value) {}

    /** Split input into runs of letters or digits. Mirrors Python rules/common/words.py:iter_words. */
    public static List<Word> iter(String input) {
        var out = new ArrayList<Word>();
        int n = input.length();
        int i = 0;
        while (i < n) {
            if (isWordChar(input.charAt(i))) {
                int s = i;
                while (i < n && isWordChar(input.charAt(i))) i++;
                out.add(new Word(s, i, input.substring(s, i)));
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=WordsTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Words.java src/test/java/io/guessit/engine/WordsTest.java
git commit -m "feat(engine): add Words token iterator"
```

---

## Task 2: `OptionsConfig.topLevel(key)` accessor

**Files:**
- Modify: `src/main/java/io/guessit/config/OptionsConfig.java`
- Test: `src/test/java/io/guessit/config/OptionsConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptionsConfigTest {
    @Test void topLevelReturnsListUnderKey() {
        var cfg = new OptionsConfig(Map.of("allowed_languages", List.of("en", "fr")));
        assertEquals(List.of("en", "fr"), cfg.topLevelList("allowed_languages"));
    }
    @Test void topLevelMissingKeyReturnsEmpty() {
        assertTrue(new OptionsConfig(Map.of()).topLevelList("missing").isEmpty());
    }
    @Test void sectionStillUnwrapsAdvancedConfig() {
        var cfg = new OptionsConfig(Map.of("advanced_config", Map.of(
            "source", Map.of("rip_prefix", "(?P<other>Rip)-?"))));
        assertEquals("(?P<other>Rip)-?", cfg.section("source").get("rip_prefix"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OptionsConfigTest`
Expected: FAIL — method `topLevelList` not found.

- [ ] **Step 3: Add accessor**

Replace the body of `OptionsConfig.java` with:

```java
package io.guessit.config;

import java.util.List;
import java.util.Map;

public record OptionsConfig(Map<String, Object> raw) {
    public OptionsConfig { raw = raw == null ? Map.of() : Map.copyOf(raw); }
    public static OptionsConfig empty() { return new OptionsConfig(Map.of()); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        var ac = raw.get("advanced_config");
        var inner = ac instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        var v = inner.get(name);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> topLevelList(String key) {
        var v = raw.get(key);
        if (v instanceof List<?> l) {
            var out = new java.util.ArrayList<String>(l.size());
            for (var e : l) if (e != null) out.add(e.toString());
            return List.copyOf(out);
        }
        return List.of();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=OptionsConfigTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/config/OptionsConfig.java src/test/java/io/guessit/config/OptionsConfigTest.java
git commit -m "feat(config): expose topLevelList accessor for allowed_* keys"
```

---

## Task 3: `OtherExtractor` (config-driven)

**Files:**
- Create: `src/main/java/io/guessit/rules/property/OtherExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/OtherExtractorTest.java`

The Python `other` rule has two flavors of entries in `advanced_config.other.other`: simple `"value": "string-pattern"` (or list) and rich `{regex|string|value|tags|...}` objects. We support exactly the simple cases needed to pass YML for now: a value mapped to either a single string, a list of strings/regex objects.

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtherExtractorTest {
    private static OptionsConfig cfg(Map<String, Object> entries) {
        return new OptionsConfig(Map.of("advanced_config",
            Map.of("other", Map.of("other", entries))));
    }

    @Test void extractsString3dPattern() {
        var ctx = new ParseContext("Movie.3D.2015",
            Options.defaults(),
            cfg(Map.of("3D", "3D")));
        new OtherExtractor().extract(ctx);
        var values = ctx.matches.named("other").map(m -> m.value().toString()).toList();
        assertEquals(List.of("3D"), values);
    }

    @Test void extractsRegexEntryWithRegexKey() {
        var ctx = new ParseContext("Movie.HDRip.2015",
            Options.defaults(),
            cfg(Map.of("Rip", Map.of("regex", List.of("(?:HD)Rip")))));
        new OtherExtractor().extract(ctx);
        var values = ctx.matches.named("other").map(m -> m.value().toString()).toList();
        assertEquals(List.of("Rip"), values);
    }

    @Test void multipleStringSynonymsAllMatch() {
        var ctx = new ParseContext("Movie.Proper.2015 Repack",
            Options.defaults(),
            cfg(Map.of("Proper", List.of("Proper", "Repack"))));
        new OtherExtractor().extract(ctx);
        ConflictSolver.solve(ctx.matches);
        var values = ctx.matches.named("other").map(m -> m.value().toString()).sorted().toList();
        assertEquals(List.of("Proper", "Proper"), values);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OtherExtractorTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class OtherExtractor implements Extractor {
    @Override public String name() { return "other"; }
    @Override public int priority() { return 1000; }

    @Override
    @SuppressWarnings("unchecked")
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("other");
        var inner = section.get("other");
        if (!(inner instanceof Map<?, ?> entries)) return;

        var input = ctx.input;
        for (var e : entries.entrySet()) {
            var value = String.valueOf(e.getKey());
            if (value.startsWith("_")) continue; // private/synthetic entry — skip simple handling
            for (var pattern : flatten(e.getValue())) {
                emit(ctx, input, value, pattern);
            }
        }
    }

    private static List<Object> flatten(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf((List<Object>) l);
        return List.of(v);
    }

    @SuppressWarnings("unchecked")
    private static void emit(ParseContext ctx, String input, String value, Object pattern) {
        if (pattern instanceof String s) {
            if (s.startsWith("re:")) emitRegex(ctx, input, value, s.substring(3));
            else emitString(ctx, input, value, s);
        } else if (pattern instanceof Map<?, ?> m) {
            var stringList = m.get("string");
            var regexList = m.get("regex");
            if (stringList instanceof String s) emitString(ctx, input, value, s);
            else if (stringList instanceof List<?> l) for (var p : l) emitString(ctx, input, value, p.toString());
            if (regexList instanceof String s) emitRegex(ctx, input, value, s);
            else if (regexList instanceof List<?> l) for (var p : l) emitRegex(ctx, input, value, p.toString());
        }
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle) {
        var opts = StringOpts.defaults()
            .withValidator(Validators.sepsSurround(input));
        for (var match : PatternMatcher.string(input, Set.of(needle), "other", opts)) {
            ctx.matches.add(match.withTags(Set.of()).withPriority(opts.priority())
                .withValue(value));
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignore) { return; }
        var opts = RegexOpts.defaults()
            .withValue(s -> value)
            .withValidator(Validators.sepsSurround(input));
        for (var match : PatternMatcher.regex(input, p, "other", opts)) {
            ctx.matches.add(match);
        }
    }
}
```

`Match.withValue` does not exist; we need to reuse the existing record. Replace the helper bodies above with the following, which build the record with the desired value:

```java
    private static void emitString(ParseContext ctx, String input, String value, String needle) {
        var opts = StringOpts.defaults()
            .withValidator(Validators.sepsSurround(input));
        var idxFrom = 0;
        var hay = input.toLowerCase(java.util.Locale.ROOT);
        var n = needle.toLowerCase(java.util.Locale.ROOT);
        while (true) {
            int i = hay.indexOf(n, idxFrom);
            if (i < 0) break;
            int end = i + n.length();
            var raw = input.substring(i, end);
            var m = new Match("other", value, i, end, raw, opts.priority(), Set.of(), false);
            if (opts.validator().test(m)) ctx.matches.add(m);
            idxFrom = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignore) { return; }
        var matcher = p.matcher(input);
        var validator = Validators.sepsSurround(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var m = new Match("other", value, s, e, input.substring(s, e), 1000, Set.of(), false);
            if (validator.test(m)) ctx.matches.add(m);
        }
    }
```

Remove the now-unused `Locale` import if your IDE flags it.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=OtherExtractorTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/OtherExtractor.java src/test/java/io/guessit/rules/property/OtherExtractorTest.java
git commit -m "feat(rules): add OtherExtractor (config-driven other property)"
```

---

## Task 4: `SourceExtractor` core patterns + ValidateSourcePrefixSuffix

This is the largest single extractor. The Python rule registers ~30 regex patterns through `build_source_pattern(*pats, prefix='', suffix='')` where `prefix` / `suffix` come from the config section (`rip_prefix`, `rip_suffix`). Each registration may produce two outputs: a `source` value plus a paired `other:Rip` (or other side-property) value when a named regex group like `(?P<other>Rip)` matches.

We model each registration as a `SourceRule` record with:
- `patterns` (raw, before `dash` rewrite)
- `prefix` / `suffix` (raw, may reference `(?P<other>...)`)
- `value` (the source name)
- `tags` (optional)
- `weak` (boolean — `tags=['weak.source']` in Python)

For Phase 2 we ship the canonical entries and run `ValidateSourcePrefixSuffix` in `postProcess`. `ValidateWeakSource` and `UltraHdBlurayRule` ship in Task 5.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/SourceExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/SourceExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceExtractorTest {
    @Test void bluRayRip() {
        var r = Guessit.parse("Movie.2015.BDRip.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
        assertEquals("Rip", r.get("other"));
    }
    @Test void webDl() {
        var r = Guessit.parse("Movie.2015.WEB-DL.mkv").toMap();
        assertEquals("Web", r.get("source"));
        assertNull(r.get("other"));
    }
    @Test void webRip() {
        var r = Guessit.parse("Movie.2015.WEBRip.mkv").toMap();
        assertEquals("Web", r.get("source"));
        assertEquals("Rip", r.get("other"));
    }
    @Test void hdtv() {
        var r = Guessit.parse("Show.S01E02.HDTV.mkv").toMap();
        assertEquals("HDTV", r.get("source"));
    }
    @Test void dvdRip() {
        var r = Guessit.parse("Movie.2015.DVDRip.mkv").toMap();
        assertEquals("DVD", r.get("source"));
        assertEquals("Rip", r.get("other"));
    }
    @Test void blurayWordSpelling() {
        var r = Guessit.parse("Movie.2015.BluRay.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
    }
    @Test void plainTvIsNotMatched() {
        // 'TV' alone with no rip_prefix or rip_suffix must not produce a source.
        var r = Guessit.parse("Some.Title.TV.mkv").toMap();
        assertNull(r.get("source"), "raw TV without rip context must not match");
    }
    @Test void brripBecomesBluray() {
        var r = Guessit.parse("Movie.2015.BRRip.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
    }
    @Test void hdcam() {
        var r = Guessit.parse("Movie.2015.HDCAM.mkv").toMap();
        assertEquals("HD Camera", r.get("source"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SourceExtractorTest`
Expected: 9 failures (extractor not registered yet).

- [ ] **Step 3: Implement `SourceExtractor`**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceExtractor implements Extractor {
    @Override public String name() { return "source"; }
    @Override public int priority() { return 1000; }

    private record Rule(List<String> patterns, String prefix, String suffix, String source,
                        String otherValue, Set<String> tags, boolean weak) {}

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("source");
        var ripPrefix = String.valueOf(section.getOrDefault("rip_prefix", "(?P<other>Rip)-?"));
        var ripSuffix = String.valueOf(section.getOrDefault("rip_suffix", "-?(?P<other>Rip)"));
        var optRipSuffix = "(?:" + ripSuffix + ")?";
        var optRipPrefix = "(?:" + ripPrefix + ")?";

        var rules = buildRules(ripPrefix, ripSuffix, optRipPrefix, optRipSuffix);

        for (var rule : rules) {
            var pattern = compileRule(rule);
            if (pattern == null) continue;
            apply(ctx, pattern, rule);
        }
    }

    private static List<Rule> buildRules(String ripPrefix, String ripSuffix,
                                         String optRipPrefix, String optRipSuffix) {
        var common = Set.<String>of();
        var rules = new ArrayList<Rule>();
        rules.add(new Rule(List.of("VHS"), "", optRipSuffix, "VHS", "Rip", common, false));
        rules.add(new Rule(List.of("CAM"), "", optRipSuffix, "Camera", "Rip", common, false));
        rules.add(new Rule(List.of("HD-?CAM"), "", optRipSuffix, "HD Camera", "Rip", common, false));
        rules.add(new Rule(List.of("TELESYNC", "TS"), "", optRipSuffix, "Telesync", "Rip", common, false));
        rules.add(new Rule(List.of("HD-?TELESYNC", "HD-?TS"), "", optRipSuffix, "HD Telesync", "Rip", common, false));
        rules.add(new Rule(List.of("WORKPRINT", "WP"), "", "", "Workprint", null, common, false));
        rules.add(new Rule(List.of("TELECINE", "TC"), "", optRipSuffix, "Telecine", "Rip", common, false));
        rules.add(new Rule(List.of("HD-?TELECINE", "HD-?TC"), "", optRipSuffix, "HD Telecine", "Rip", common, false));
        rules.add(new Rule(List.of("PPV"), "", optRipSuffix, "Pay-per-view", "Rip", common, false));
        rules.add(new Rule(List.of("SD-?TV"), "", optRipSuffix, "TV", "Rip", common, false));
        // Bare "TV" only with rip prefix or suffix.
        rules.add(new Rule(List.of("TV"), "", ripSuffix, "TV", "Rip", common, false));
        rules.add(new Rule(List.of("TV", "SD-?TV"), ripPrefix, "", "TV", "Rip", common, false));
        rules.add(new Rule(List.of("TV-?(?=Dub)"), "", "", "TV", null, common, false));
        rules.add(new Rule(List.of("DVB", "PD-?TV"), "", optRipSuffix, "Digital TV", "Rip", common, false));
        rules.add(new Rule(List.of("DVD"), "", optRipSuffix, "DVD", "Rip", common, false));
        rules.add(new Rule(List.of("DM"), "", optRipSuffix, "Digital Master", "Rip", common, false));
        rules.add(new Rule(List.of("VIDEO-?TS", "DVD-?R(?:$|(?!E))", "DVD-?9", "DVD-?5"),
            "", "", "DVD", null, common, false));
        rules.add(new Rule(List.of("HD-?TV"), "", optRipSuffix, "HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("TV-?HD"), "", ripSuffix, "HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("TV"), "", "-?(?P<other>Rip-?HD)", "HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("VOD"), "", optRipSuffix, "Video on Demand", "Rip", common, false));
        rules.add(new Rule(List.of("WEB", "WEB-?DL"), "", ripSuffix, "Web", "Rip", common, false));
        rules.add(new Rule(List.of("WEB-?(?P<another>Cap)"), "", optRipSuffix, "Web", "Rip", common, false));
        rules.add(new Rule(List.of("WEB-?DL", "WEB-?U?HD", "DL-?WEB", "DL(?=-?Mux)"),
            "", "", "Web", null, common, false));
        rules.add(new Rule(List.of("WEB"), "", "", "Web", null, Set.of("weak.source"), true));
        rules.add(new Rule(List.of("HD-?DVD"), "", optRipSuffix, "HD-DVD", "Rip", common, false));
        rules.add(new Rule(List.of("Blu-?ray", "BD", "BD[59]", "BD25", "BD50"),
            "", optRipSuffix, "Blu-ray", "Rip", common, false));
        rules.add(new Rule(List.of("(?P<another>BR)-?(?=Scr(?:eener)?)", "(?P<another>BR)-?(?=Mux)"),
            "", "", "Blu-ray", null, common, false));
        rules.add(new Rule(List.of("(?P<another>BR)"), "", ripSuffix, "Blu-ray", "Rip", common, false));
        rules.add(new Rule(List.of("Ultra-?Blu-?ray", "Blu-?ray-?Ultra"), "", "", "Ultra HD Blu-ray", null, common, false));
        rules.add(new Rule(List.of("AHDTV"), "", "", "Analog HDTV", null, common, false));
        rules.add(new Rule(List.of("UHD-?TV"), "", optRipSuffix, "Ultra HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("UHD"), "", ripSuffix, "Ultra HDTV", "Rip", common, false));
        rules.add(new Rule(List.of("DSR", "DTH"), "", optRipSuffix, "Satellite", "Rip", common, false));
        rules.add(new Rule(List.of("DSR?", "SAT"), "", ripSuffix, "Satellite", "Rip", common, false));
        return rules;
    }

    private static Pattern compileRule(Rule rule) {
        var alt = String.join("|", rule.patterns());
        var src = rule.prefix() + "(" + alt + ")" + rule.suffix();
        try {
            return Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void apply(ParseContext ctx, Pattern p, Rule rule) {
        var input = ctx.input;
        var validator = Validators.sepsBefore(input).or(Validators.sepsAfter(input));
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var sourceMatch = new Match("source", rule.source(), s, e,
                input.substring(s, e), 1000, rule.tags(), false);
            if (!validator.test(sourceMatch)) continue;
            ctx.matches.add(sourceMatch);
            // If "other" group matched, emit a paired other match.
            if (rule.otherValue() != null) {
                int os = groupStart(matcher, "other");
                int oe = groupEnd(matcher, "other");
                if (os >= 0 && oe > os) {
                    ctx.matches.add(new Match("other", rule.otherValue(), os, oe,
                        input.substring(os, oe), 1000, Set.of("coexist"), false));
                }
            }
        }
    }

    private static int groupStart(Matcher m, String name) {
        try { return m.start(name); } catch (IllegalArgumentException | IllegalStateException e) { return -1; }
    }
    private static int groupEnd(Matcher m, String name) {
        try { return m.end(name); } catch (IllegalArgumentException | IllegalStateException e) { return -1; }
    }

    /** Replicates Python ValidateSourcePrefixSuffix. */
    @Override
    public void postProcess(ParseContext ctx) {
        var input = ctx.input;
        var sources = ctx.matches.named("source").toList();
        var toRemove = new ArrayList<Match>();
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        for (var s : sources) {
            if (!sepsBefore.test(s)) {
                if (!hasNeighborTag(ctx, s.start() - 1, "source-prefix")) toRemove.add(s);
                continue;
            }
            if (!sepsAfter.test(s)) {
                if (!hasNeighborTag(ctx, s.end(), "source-suffix")) toRemove.add(s);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean hasNeighborTag(ParseContext ctx, int pos, String tag) {
        return ctx.matches.all().anyMatch(m -> m.tags().contains(tag) && m.start() <= pos && pos <= m.end());
    }
}
```

Notes:
- Sources are tagged `coexist` only on their paired `other` companion to allow two simultaneous Match entries; the source itself goes through normal conflict resolution.
- `dash(src)` uses the existing `Abbreviations.dash` helper from Plan 1, which converts literal `-` into `[<seps_no_fs>]*`.

- [ ] **Step 4: Register temporarily**

Open `src/main/java/io/guessit/rules/Rules.java` and add `new SourceExtractor()` and `new OtherExtractor()` to `allInOrder()`:

```java
    public static List<Extractor> allInOrder() {
        return List.of(
            new YearExtractor(),
            new ScreenSizeExtractor(),
            new VideoCodecExtractor(),
            new AudioCodecExtractor(),
            new ContainerExtractor(),
            new OtherExtractor(),
            new SourceExtractor()
        );
    }
```

- [ ] **Step 5: Run tests**

Run: `mvn -q test -Dtest=SourceExtractorTest`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/SourceExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/SourceExtractorTest.java
git commit -m "feat(rules): add SourceExtractor with rip_prefix/suffix and ValidateSourcePrefixSuffix"
```

---

## Task 5: Source extras — `ValidateWeakSource` + `UltraHdBlurayRule`

`weak.source` ("WEB" alone) must be dropped if there is another source later in the same filepart with a hole between them. `UltraHdBlurayRule` upgrades a `Blu-ray` source to `Ultra HD Blu-ray` when an `other:Ultra HD` neighbor or a `screen_size:2160p` peer exists in the same filepart.

For Phase 2 we implement only the `screen_size=2160p` branch (the `other:Ultra HD` branch ships once `Ultra HD` lands as an `other` value in Phase 3+). The Python rule still fires for releases like `Movie.2015.2160p.BluRay.mkv`, which is what most YML cases need.

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/SourceExtractor.java`
- Modify: `src/test/java/io/guessit/rules/property/SourceExtractorTest.java`

- [ ] **Step 1: Write the failing test (extend existing test class)**

Append to `SourceExtractorTest.java`:

```java
    @Test void weakWebRemovedWhenStrongSourceFollows() {
        // "WEB" before BluRay in same filepart with hole between → weak.source dropped.
        var r = Guessit.parse("Some.WEB.Title.2015.BluRay.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
    }

    @Test void weakWebKeptWhenAlone() {
        var r = Guessit.parse("Movie.2015.WEB.mkv").toMap();
        assertEquals("Web", r.get("source"));
    }

    @Test void ultraHdBluray2160p() {
        var r = Guessit.parse("Movie.2015.2160p.BluRay.mkv").toMap();
        assertEquals("Ultra HD Blu-ray", r.get("source"));
        assertEquals("2160p", r.get("screen_size"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SourceExtractorTest`
Expected: 3 new failures.

- [ ] **Step 3: Extend `postProcess` with weak-source + UHD-Blu-ray logic**

Replace the current `postProcess` method body in `SourceExtractor.java` with:

```java
    @Override
    public void postProcess(ParseContext ctx) {
        validatePrefixSuffix(ctx);
        validateWeakSource(ctx);
        upgradeUltraHdBluray(ctx);
    }

    private void validatePrefixSuffix(ParseContext ctx) {
        var input = ctx.input;
        var sources = ctx.matches.named("source").toList();
        var toRemove = new ArrayList<Match>();
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        for (var s : sources) {
            if (!sepsBefore.test(s)) {
                if (!hasNeighborTag(ctx, s.start() - 1, "source-prefix")) { toRemove.add(s); continue; }
            }
            if (!sepsAfter.test(s)) {
                if (!hasNeighborTag(ctx, s.end(), "source-suffix")) toRemove.add(s);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void validateWeakSource(ParseContext ctx) {
        var weaks = ctx.matches.named("source")
            .filter(m -> m.tags().contains("weak.source"))
            .toList();
        if (weaks.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var weak : weaks) {
                if (!filepart.covers(weak.start(), weak.end())) continue;
                boolean later = ctx.matches.named("source")
                    .anyMatch(m -> m != weak && m.start() >= weak.end() && m.end() <= filepart.end());
                if (!later) continue;
                // Holes between filepart.start..weak.start (any non-whitespace text exists)?
                var pre = ctx.input.substring(filepart.start(), weak.start());
                if (!pre.isBlank()) toRemove.add(weak);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void upgradeUltraHdBluray(ParseContext ctx) {
        var bds = ctx.matches.named("source")
            .filter(m -> "Blu-ray".equals(m.value()))
            .toList();
        if (bds.isEmpty()) return;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            for (var bd : bds) {
                if (!filepart.covers(bd.start(), bd.end())) continue;
                boolean has2160p = ctx.matches.named("screen_size")
                    .anyMatch(m -> "2160p".equals(m.value()) && filepart.covers(m.start(), m.end()));
                if (!has2160p) continue;
                ctx.matches.replace(bd, new Match("source", "Ultra HD Blu-ray",
                    bd.start(), bd.end(), bd.raw(), bd.priority(), bd.tags(), bd.isPrivate()));
            }
        }
    }
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=SourceExtractorTest`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/SourceExtractor.java \
        src/test/java/io/guessit/rules/property/SourceExtractorTest.java
git commit -m "feat(rules): SourceExtractor adds weak-source pruning + UHD Blu-ray upgrade"
```

---

## Task 6: `WebsiteExtractor`

Python builds two regex patterns per `safe_subdomains` / `safe_tlds` config: one full TLD list, one safe-subdomain list. We mirror that closely. `PreferTitleOverWebsite` defers to Phase 4 (needs title); we skip it for now.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/WebsiteExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/WebsiteExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebsiteExtractorTest {
    @Test void detectsSafeTld() {
        var r = Guessit.parse("Show.S01.example.com.WEB.mkv").toMap();
        assertEquals("example.com", r.get("website"));
    }
    @Test void detectsTld() {
        var r = Guessit.parse("Show.S01 [www.tracker.io].mkv").toMap();
        assertNotNull(r.get("website"));
        assertTrue(r.get("website").toString().toLowerCase().endsWith("tracker.io"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=WebsiteExtractorTest`
Expected: 2 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class WebsiteExtractor implements Extractor {
    @Override public String name() { return "website"; }
    @Override public int priority() { return 1000; }

    private static final List<String> TLDS = loadTlds();

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("website");
        var safeTlds = stringList(section.get("safe_tlds"));
        var safeSubdomains = stringList(section.get("safe_subdomains"));
        var safePrefixes = stringList(section.get("safe_prefixes"));
        var input = ctx.input;

        // Pattern 1: explicit safe-subdomain prefix + multi-level + any TLD
        var p1 = "(?:[^a-z0-9]|^)((?:" + or(safeSubdomains) + "\\.)+(?:[a-z0-9-]+\\.)+(?:" + or(TLDS) + "))(?:[^a-z0-9]|$)";
        // Pattern 2: any host + safe TLD only
        var p2 = "(?:[^a-z0-9]|^)((?:" + or(safeSubdomains) + "\\.)*[a-z0-9-]+\\.(?:" + or(safeTlds) + "))(?:[^a-z0-9]|$)";
        // Pattern 3: domain.{safePrefix}.{tld}
        var p3 = "(?:[^a-z0-9]|^)((?:" + or(safeSubdomains) + "\\.)*[a-z0-9-]+\\.(?:" + or(safePrefixes) + "\\.)+(?:" + or(TLDS) + "))(?:[^a-z0-9]|$)";

        for (var src : List.of(p1, p2, p3)) {
            var matcher = Pattern.compile(src, Pattern.CASE_INSENSITIVE).matcher(input);
            while (matcher.find()) {
                int s = matcher.start(1);
                int e = matcher.end(1);
                ctx.matches.add(new Match("website", input.substring(s, e), s, e,
                    input.substring(s, e), 1000, Set.of(), false));
            }
        }
    }

    private static String or(List<String> items) {
        if (items.isEmpty()) return "(?!)";
        var quoted = new ArrayList<String>(items.size());
        for (var s : items) quoted.add(Pattern.quote(s));
        return "(?:" + String.join("|", quoted) + ")";
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) {
            var out = new ArrayList<String>(l.size());
            for (var v : l) out.add(v.toString().toLowerCase(Locale.ROOT));
            return out;
        }
        return List.of();
    }

    private static List<String> loadTlds() {
        var out = new ArrayList<String>();
        try (var in = WebsiteExtractor.class.getResourceAsStream("/io/guessit/data/tlds-alpha-by-domain.txt");
             var r = new BufferedReader(new InputStreamReader(java.util.Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            String line; boolean first = true;
            while ((line = r.readLine()) != null) {
                if (line.contains("--")) continue;
                if (first) { first = false; continue; }
                var trim = line.trim();
                if (!trim.isEmpty()) out.add(trim.toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot load TLD list", e);
        }
        return out;
    }
}
```

- [ ] **Step 4: Register and run**

Edit `Rules.java`:

```java
    public static List<Extractor> allInOrder() {
        return List.of(
            new YearExtractor(),
            new ScreenSizeExtractor(),
            new VideoCodecExtractor(),
            new AudioCodecExtractor(),
            new ContainerExtractor(),
            new OtherExtractor(),
            new WebsiteExtractor(),
            new SourceExtractor()
        );
    }
```

Run: `mvn -q test -Dtest=WebsiteExtractorTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/WebsiteExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/WebsiteExtractorTest.java
git commit -m "feat(rules): add WebsiteExtractor (TLD + safe-tld patterns)"
```

---

## Task 7: `StreamingServiceExtractor`

Python's `streaming_service` config (`advanced_config.streaming_service`) is a flat map of `{ServiceName: pattern-or-list}`. Each value can be a plain string, a string with `re:` prefix, a list of strings, or a list of `{string|regex|...}` dicts. We support strings, `re:`-prefixed strings, and lists thereof.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/StreamingServiceExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/StreamingServiceExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingServiceExtractorTest {
    @Test void amzn() {
        var r = Guessit.parse("Show.S01.AMZN.WEB-DL.mkv").toMap();
        assertEquals("Amazon Prime", r.get("streaming_service"));
    }
    @Test void atvp() {
        var r = Guessit.parse("Show.S01.ATVP.WEB-DL.mkv").toMap();
        assertEquals("AppleTV", r.get("streaming_service"));
    }
    @Test void disneyPlus() {
        var r = Guessit.parse("Show.S01.DSNP.WEB-DL.mkv").toMap();
        assertEquals("Disney+", r.get("streaming_service"));
    }
    @Test void notMatchedWithoutSourceContext() {
        // CC alone with no source nearby should be filtered by ValidateStreamingService
        var r = Guessit.parse("File.CC.foo").toMap();
        assertNotEquals("Comedy Central", r.get("streaming_service"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=StreamingServiceExtractorTest`
Expected: 4 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class StreamingServiceExtractor implements Extractor {
    @Override public String name() { return "streaming_service"; }
    @Override public int priority() { return 1000; }

    @Override
    @SuppressWarnings("unchecked")
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("streaming_service");
        if (section.isEmpty()) return;

        var input = ctx.input;
        for (var e : section.entrySet()) {
            String value = String.valueOf(e.getKey());
            for (var pat : flatten(e.getValue())) emit(ctx, input, value, pat);
        }
    }

    private static List<Object> flatten(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf((List<Object>) l);
        return List.of(v);
    }

    private static void emit(ParseContext ctx, String input, String value, Object pat) {
        if (pat instanceof String s) {
            if (s.startsWith("re:")) emitRegex(ctx, input, value, s.substring(3));
            else emitString(ctx, input, value, s);
        } else if (pat instanceof Map<?, ?> m) {
            var s = m.get("string");
            var r = m.get("regex");
            if (s instanceof String str) emitString(ctx, input, value, str);
            else if (s instanceof List<?> l) for (var x : l) emitString(ctx, input, value, x.toString());
            if (r instanceof String str) emitRegex(ctx, input, value, str);
            else if (r instanceof List<?> l) for (var x : l) emitRegex(ctx, input, value, x.toString());
        }
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle) {
        var hay = input.toLowerCase(Locale.ROOT);
        var n = needle.toLowerCase(Locale.ROOT);
        var validator = Validators.sepsSurround(input);
        int from = 0;
        while (true) {
            int i = hay.indexOf(n, from);
            if (i < 0) break;
            int e = i + n.length();
            var m = new Match("streaming_service", value, i, e, input.substring(i, e),
                1000, Set.of("source-prefix"), false);
            if (validator.test(m)) ctx.matches.add(m);
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception ex) { return; }
        var validator = Validators.sepsSurround(input);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var m = new Match("streaming_service", value, s, e, input.substring(s, e),
                1000, Set.of("source-prefix"), false);
            if (validator.test(m)) ctx.matches.add(m);
        }
    }

    /** Replicates Python ValidateStreamingService — service must abut a source-tagged neighbor. */
    @Override
    public void postProcess(ParseContext ctx) {
        var services = ctx.matches.named("streaming_service").toList();
        if (services.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var s : services) {
            boolean hasNext = adjacent(ctx, s.end(), "streaming_service.suffix")
                || hasSourceNear(ctx, s.end(), 0, ctx.input.length());
            boolean hasPrev = adjacent(ctx, s.start(), "streaming_service.prefix")
                || hasSourceNear(ctx, 0, s.start(), s.start());
            if (!hasNext && !hasPrev) toRemove.add(s);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static boolean adjacent(ParseContext ctx, int pos, String tag) {
        return ctx.matches.all().anyMatch(m -> m.tags().contains(tag) && Math.abs(m.start() - pos) <= 1);
    }

    private static boolean hasSourceNear(ParseContext ctx, int from, int to, int anchor) {
        return ctx.matches.named("source").anyMatch(m -> m.start() >= from && m.end() <= to
            && Math.abs(m.start() - anchor) < 20);
    }
}
```

- [ ] **Step 4: Register and run**

Edit `Rules.java` `allInOrder()`:

```java
            new StreamingServiceExtractor(),
            new SourceExtractor()
```

(Streaming service before Source so its matches participate in conflict resolution against `WEB-DL` etc.)

Run: `mvn -q test -Dtest=StreamingServiceExtractorTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/StreamingServiceExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/StreamingServiceExtractorTest.java
git commit -m "feat(rules): add StreamingServiceExtractor with ValidateStreamingService"
```

---

## Task 8: `LanguageExtractor` core (language matches via Words + LanguageRegistry)

For Phase 2 we ship the canonical Python flow without extended-word merging (`pt-BR`, `soft subs`); those land in Phase 5 polish. We use `Words.iter` over the input, look each token up via `LanguageRegistry.find`, and emit a `language` match if the resolved Language has an alpha2 / alpha3 / name / alias entry within `allowed_languages`.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/LanguageExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/LanguageExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.lang.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LanguageExtractorTest {
    @Test void englishAlpha2() {
        var r = Guessit.parse("Show.S01.ENG.HDTV.mkv").toMap();
        assertEquals(new Language("en", "eng", "English"), r.get("language"));
    }
    @Test void frenchAliasVf() {
        var r = Guessit.parse("Movie.2015.VF.BluRay.mkv").toMap();
        assertEquals(new Language("fr", "fra", "French"), r.get("language"));
    }
    @Test void multipleLanguagesCollapseToList() {
        var r = Guessit.parse("Movie.2015.ENG.FRE.BluRay.mkv").toMap();
        var languages = (List<?>) r.get("language");
        assertNotNull(languages);
        assertEquals(2, languages.size());
    }
    @Test void undeterminedDroppedWhenRealLangPresent() {
        // "und" should be dropped if a regular language match exists.
        var r = Guessit.parse("Show.UND.ENG.HDTV.mkv").toMap();
        assertEquals(new Language("en", "eng", "English"), r.get("language"));
    }
    @Test void languageNotInAllowedListIgnored() {
        // "kor" is not in default allowed_languages → dropped.
        var r = Guessit.parse("Show.KOR.HDTV.mkv").toMap();
        assertNull(r.get("language"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=LanguageExtractorTest`
Expected: 5 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class LanguageExtractor implements Extractor {
    @Override public String name() { return "language"; }
    @Override public int priority() { return 1000; }

    private static final String UND_NAME = "Undetermined";
    private static final String MUL_NAME = "Multiple languages";

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedLanguages(ctx);
        if (allowed.isEmpty()) return;

        var registry = LanguageRegistry.instance();
        var input = ctx.input;

        // Track language affixes (subtitle prefix/suffix, language suffix) as private matches
        // so the post-rules can rename neighbors.
        emitAffixes(ctx);

        for (var word : Words.iter(input)) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            // Skip pure-digit tokens (cheap pre-filter — registry would not resolve them anyway).
            if (lower.chars().allMatch(Character::isDigit)) continue;
            var lang = registry.find(lower).orElse(null);
            if (lang == null) continue;
            if (!isAllowed(lang, allowed)) continue;
            ctx.matches.add(new Match("language", lang, word.start(), word.end(),
                input.substring(word.start(), word.end()), 1000, Set.of(), false));
        }
    }

    private static List<String> allowedLanguages(ParseContext ctx) {
        var explicit = ctx.options.allowedLanguages();
        if (!explicit.isEmpty()) return explicit;
        return ctx.config.topLevelList("allowed_languages");
    }

    private static boolean isAllowed(Language lang, List<String> allowed) {
        var lc = new HashSet<String>(allowed.size());
        for (var s : allowed) lc.add(s.toLowerCase(Locale.ROOT));
        if (lang.alpha2() != null && lc.contains(lang.alpha2().toLowerCase(Locale.ROOT))) return true;
        if (lang.alpha3() != null && lc.contains(lang.alpha3().toLowerCase(Locale.ROOT))) return true;
        if (lang.name() != null && lc.contains(lang.name().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void emitAffixes(ParseContext ctx) {
        var section = ctx.config.section("language");
        var subtitleAffixes = stringList(section.get("subtitle_affixes"));
        var subtitlePrefixes = combine(subtitleAffixes, stringList(section.get("subtitle_prefixes")));
        var subtitleSuffixes = combine(subtitleAffixes, stringList(section.get("subtitle_suffixes")));
        var languageAffixes = stringList(section.get("language_affixes"));
        var languageSuffixes = combine(languageAffixes, stringList(section.get("language_suffixes")));
        var languagePrefixes = combine(languageAffixes, stringList(section.get("language_prefixes")));

        emitAffixGroup(ctx, subtitlePrefixes, "subtitle_language.prefix", Set.of("release-group-prefix"));
        emitAffixGroup(ctx, subtitleSuffixes, "subtitle_language.suffix", Set.of());
        emitAffixGroup(ctx, languagePrefixes, "language.prefix",          Set.of());
        emitAffixGroup(ctx, languageSuffixes, "language.suffix",          Set.of("source-suffix"));
    }

    private static void emitAffixGroup(ParseContext ctx, List<String> affixes, String name, Set<String> tags) {
        if (affixes.isEmpty()) return;
        var validator = Validators.sepsSurround(ctx.input);
        var hay = ctx.input.toLowerCase(Locale.ROOT);
        for (var aff : affixes) {
            var n = aff.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int i = hay.indexOf(n, from);
                if (i < 0) break;
                int e = i + n.length();
                var m = new Match(name, aff, i, e, ctx.input.substring(i, e), 1000, tags, true);
                if (validator.test(m)) ctx.matches.add(m);
                from = i + 1;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) {
            var out = new ArrayList<String>(l.size());
            for (var v : l) if (v != null) out.add(v.toString());
            return out;
        }
        return List.of();
    }

    private static List<String> combine(List<String> a, List<String> b) {
        if (a.isEmpty()) return b; if (b.isEmpty()) return a;
        var out = new ArrayList<String>(a.size() + b.size());
        out.addAll(a); out.addAll(b);
        return out;
    }

    /** Drop "Undetermined" matches when a real language is present. */
    @Override
    public void postProcess(ParseContext ctx) {
        var langs = ctx.matches.named("language").toList();
        boolean hasReal = langs.stream().anyMatch(m -> m.value() instanceof Language l
            && !UND_NAME.equals(l.name()) && !MUL_NAME.equals(l.name()));
        if (hasReal) {
            for (var m : langs) {
                if (m.value() instanceof Language l && UND_NAME.equals(l.name())) {
                    ctx.matches.remove(m);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Register and run**

Edit `Rules.java`:

```java
            new LanguageExtractor(),
            new StreamingServiceExtractor(),
            new SourceExtractor()
```

Run: `mvn -q test -Dtest=LanguageExtractorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/LanguageExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/LanguageExtractorTest.java
git commit -m "feat(rules): add LanguageExtractor (allowed-language filter, affix awareness)"
```

---

## Task 9: Subtitle-language conversions (`SubtitlePrefixLanguageRule`, `SubtitleSuffixLanguageRule`, `SubtitleExtensionRule`)

Add three Python-equivalent conversions in `LanguageExtractor.postProcess`, after the undetermined-drop.

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/LanguageExtractor.java`
- Modify: `src/test/java/io/guessit/rules/property/LanguageExtractorTest.java`

- [ ] **Step 1: Append failing tests**

Append to `LanguageExtractorTest.java`:

```java
    @Test void subtitlePrefixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.VOST.ENG.HDTV.mkv").toMap();
        assertNull(r.get("language"));
        assertEquals(new io.guessit.lang.Language("en", "eng", "English"), r.get("subtitle_language"));
    }

    @Test void subtitleSuffixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.ENG.SUB.HDTV.mkv").toMap();
        assertNull(r.get("language"));
        assertEquals(new io.guessit.lang.Language("en", "eng", "English"), r.get("subtitle_language"));
    }

    @Test void subtitleExtensionPromotesPreviousLanguage() {
        var r = Guessit.parse("Show.S01.ENG.srt").toMap();
        assertNull(r.get("language"));
        assertEquals(new io.guessit.lang.Language("en", "eng", "English"), r.get("subtitle_language"));
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -q test -Dtest=LanguageExtractorTest`
Expected: 3 new failures.

- [ ] **Step 3: Extend `postProcess`**

Replace the `postProcess` method body in `LanguageExtractor.java` with:

```java
    @Override
    public void postProcess(ParseContext ctx) {
        renameWithSubtitlePrefix(ctx);
        renameWithSubtitleSuffix(ctx);
        renameWithSubtitleExtension(ctx);
        dropUndeterminedWhenRealLangPresent(ctx);
        dropPrivateAffixes(ctx);
    }

    private void renameWithSubtitlePrefix(ParseContext ctx) {
        var prefixes = ctx.matches.all()
            .filter(m -> "subtitle_language.prefix".equals(m.name()))
            .toList();
        if (prefixes.isEmpty()) return;
        var langs = ctx.matches.named("language").toList();
        for (var prefix : prefixes) {
            // Find first language match starting at or after prefix.end
            var lang = langs.stream()
                .filter(l -> l.start() >= prefix.end())
                .min((a, b) -> Integer.compare(a.start(), b.start()))
                .orElse(null);
            if (lang == null) continue;
            // Must be adjacent (only seps between).
            if (!ctx.input.substring(prefix.end(), lang.start()).chars().allMatch(c -> Seps.isSep((char) c))) continue;
            ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
                lang.start(), lang.end(), lang.raw(), lang.priority(), lang.tags(), false));
        }
    }

    private void renameWithSubtitleSuffix(ParseContext ctx) {
        var suffixes = ctx.matches.all()
            .filter(m -> "subtitle_language.suffix".equals(m.name()))
            .toList();
        if (suffixes.isEmpty()) return;
        var langs = ctx.matches.named("language").toList();
        for (var suffix : suffixes) {
            var lang = langs.stream()
                .filter(l -> l.end() <= suffix.start())
                .max((a, b) -> Integer.compare(a.end(), b.end()))
                .orElse(null);
            if (lang == null) continue;
            if (!ctx.input.substring(lang.end(), suffix.start()).chars().allMatch(c -> Seps.isSep((char) c))) continue;
            ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
                lang.start(), lang.end(), lang.raw(), lang.priority(), lang.tags(), false));
        }
    }

    private void renameWithSubtitleExtension(ParseContext ctx) {
        var subtitleExt = ctx.matches.named("container")
            .filter(m -> m.tags().contains("subtitle") && m.tags().contains("extension"))
            .findFirst()
            .orElse(null);
        if (subtitleExt == null) return;
        var lang = ctx.matches.named("language")
            .filter(l -> l.end() <= subtitleExt.start())
            .max((a, b) -> Integer.compare(a.end(), b.end()))
            .orElse(null);
        if (lang == null) return;
        ctx.matches.replace(lang, new Match("subtitle_language", lang.value(),
            lang.start(), lang.end(), lang.raw(), lang.priority(), lang.tags(), false));
    }

    private void dropUndeterminedWhenRealLangPresent(ParseContext ctx) {
        for (var prop : List.of("language", "subtitle_language")) {
            var matches = ctx.matches.named(prop).toList();
            boolean hasReal = matches.stream().anyMatch(m -> m.value() instanceof Language l
                && !UND_NAME.equals(l.name()) && !MUL_NAME.equals(l.name()));
            if (hasReal) {
                for (var m : matches) {
                    if (m.value() instanceof Language l && UND_NAME.equals(l.name())) {
                        ctx.matches.remove(m);
                    }
                }
            }
        }
    }

    private void dropPrivateAffixes(ParseContext ctx) {
        // Affix matches were emitted as private to drive renaming; remove them now.
        var toRemove = ctx.matches.all()
            .filter(m -> m.name().endsWith(".prefix") || m.name().endsWith(".suffix"))
            .filter(m -> m.name().startsWith("language") || m.name().startsWith("subtitle_language"))
            .toList();
        for (var m : toRemove) ctx.matches.remove(m);
    }
```

You may need an `import io.guessit.engine.Seps;`. Drop the unused `HashSet`/`Objects` imports if your IDE flags them.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=LanguageExtractorTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/LanguageExtractor.java \
        src/test/java/io/guessit/rules/property/LanguageExtractorTest.java
git commit -m "feat(rules): LanguageExtractor handles subtitle prefix/suffix/extension promotion"
```

---

## Task 10: `CountryExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/CountryExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/CountryExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.lang.Country;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountryExtractorTest {
    @Test void usCountry() {
        var r = Guessit.parse("Show.S01.US.WEB-DL.mkv").toMap();
        assertEquals(new Country("US", "United States"), r.get("country"));
    }
    @Test void ukCountry() {
        var r = Guessit.parse("Show.S01.UK.HDTV.mkv").toMap();
        assertNotNull(r.get("country"));
    }
    @Test void disallowedCountryIgnored() {
        var r = Guessit.parse("Show.S01.JP.HDTV.mkv").toMap();
        // JP not in default allowed_countries → not picked up.
        assertNull(r.get("country"));
    }
    @Test void englishLanguageNotMisreadAsCountry() {
        // "EN" is a language code, must not become country.
        var r = Guessit.parse("Show.S01.EN.HDTV.mkv").toMap();
        assertNull(r.get("country"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=CountryExtractorTest`
Expected: 4 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.lang.Country;
import io.guessit.lang.LanguageRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CountryExtractor implements Extractor {
    @Override public String name() { return "country"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var allowed = allowedCountries(ctx);
        if (allowed.isEmpty()) return;

        var allowedLc = new HashSet<String>(allowed.size());
        for (var s : allowed) allowedLc.add(s.toLowerCase(Locale.ROOT));

        var registry = LanguageRegistry.instance();
        var input = ctx.input;

        for (var word : Words.iter(input)) {
            var lower = word.value().toLowerCase(Locale.ROOT);
            if (lower.chars().allMatch(Character::isDigit)) continue;
            var country = registry.findCountry(lower).orElse(null);
            if (country == null) continue;
            if (!allowedLc.contains(country.alpha2().toLowerCase(Locale.ROOT))
                    && !allowedLc.contains(country.name().toLowerCase(Locale.ROOT))) continue;
            ctx.matches.add(new Match("country", country, word.start(), word.end(),
                input.substring(word.start(), word.end()), 1000, Set.of(), false));
        }
    }

    private static List<String> allowedCountries(ParseContext ctx) {
        var explicit = ctx.options.allowedCountries();
        if (!explicit.isEmpty()) return explicit;
        return ctx.config.topLevelList("allowed_countries");
    }

    /** Resolve "country vs language" conflict: prefer language unless country is US/GB. */
    @Override
    public void postProcess(ParseContext ctx) {
        var countries = ctx.matches.named("country").toList();
        var langs = ctx.matches.named("language").toList();
        var toRemove = new java.util.ArrayList<Match>();
        for (var c : countries) {
            for (var l : langs) {
                if (c.start() == l.start() && c.end() == l.end()) {
                    if (c.value() instanceof Country cc
                            && !"US".equals(cc.alpha2()) && !"GB".equals(cc.alpha2())) {
                        toRemove.add(c);
                    } else {
                        toRemove.add(l);
                    }
                }
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register and run**

Edit `Rules.java`:

```java
            new LanguageExtractor(),
            new CountryExtractor(),
            new StreamingServiceExtractor(),
```

Run: `mvn -q test -Dtest=CountryExtractorTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/CountryExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/CountryExtractorTest.java
git commit -m "feat(rules): add CountryExtractor with language-vs-country conflict resolution"
```

---

## Task 11: `ReleaseGroupExtractor` (expected_group + dash-separated + scene + anime-marker)

This is the trickiest rule because Python's `SceneReleaseGroup` depends on title presence to anchor the trailing hole. Title comes in Plan 4. We approximate with a "trailing scene" heuristic: take the last hole inside a filepart whose adjacent prior match is one of the canonical scene-prev names (`source`, `video_codec`, `audio_codec`, `audio_channels`, `audio_profile`, `screen_size`, `language`, `subtitle_language`, `other`, `container`).

**Files:**
- Create: `src/main/java/io/guessit/rules/property/ReleaseGroupExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/ReleaseGroupExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseGroupExtractorTest {
    @Test void dashSeparatedAtEnd() {
        var r = Guessit.parse("Series.S01E02.Pilot.DVDRip.x264-CS.mkv").toMap();
        assertEquals("CS", r.get("release_group"));
    }
    @Test void dashSeparatedAtBeginning() {
        var r = Guessit.parse("abc-the.title.name.1983.1080p.bluray.x264.mkv").toMap();
        assertEquals("abc", r.get("release_group"));
    }
    @Test void scene() {
        var r = Guessit.parse("Something.XViD-ReleaseGroup.mkv").toMap();
        assertEquals("ReleaseGroup", r.get("release_group"));
    }
    @Test void animeBracketedAtStart() {
        var r = Guessit.parse("[ReleaseGroup] Something.S01E01.mkv").toMap();
        assertEquals("ReleaseGroup", r.get("release_group"));
    }
    @Test void expectedGroupWins() {
        var opts = Options.builder().expectedGroup(List.of("MyGroup")).build();
        var r = Guessit.parse("Movie.MyGroup.x264.mkv", opts).toMap();
        assertEquals("MyGroup", r.get("release_group"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ReleaseGroupExtractorTest`
Expected: 5 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ReleaseGroupExtractor implements Extractor {
    private static final Set<String> SCENE_PREV = Set.of(
        "video_codec", "source", "video_api", "audio_codec", "audio_profile", "video_profile",
        "audio_channels", "screen_size", "other", "container",
        "language", "subtitle_language");

    @Override public String name() { return "release_group"; }
    @Override public int priority() { return 1000; }

    /** Phase 2: extractor only emits expected_group; the dash/scene/anime variants live in postProcess. */
    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedGroup();
        if (expected.isEmpty()) return;
        var input = ctx.input;
        var validator = Validators.sepsSurround(input);
        for (var name : expected) {
            int from = 0;
            while (true) {
                int idx = input.toLowerCase().indexOf(name.toLowerCase(), from);
                if (idx < 0) break;
                int end = idx + name.length();
                var m = new Match("release_group", name, idx, end, input.substring(idx, end),
                    2000, Set.of("expected"), false);
                if (validator.test(m)) ctx.matches.add(m);
                from = idx + 1;
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        // If expected_group already produced one, skip the heuristics.
        if (ctx.matches.named("release_group").findAny().isPresent()) return;
        var matched = detectDashSeparated(ctx);
        if (!matched) matched = detectScene(ctx);
        if (!matched) detectAnimeBrackets(ctx);
    }

    private boolean detectDashSeparated(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var part = input.substring(filepart.start(), filepart.end());
            // Trailing dash group: ".x264-Group.ext" or "...-Group.ext"
            var ext = ctx.matches.named("container")
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains("extension"))
                .findFirst().orElse(null);
            int end = ext != null ? ext.start() : filepart.end();
            int dash = input.lastIndexOf('-', end - 1);
            if (dash > filepart.start() && dash < end - 1) {
                var candidate = input.substring(dash + 1, end);
                if (validGroupName(candidate)) {
                    var m = new Match("release_group", candidate.trim(), dash + 1, end,
                        candidate, 1500, Set.of("scene"), false);
                    ctx.matches.add(m);
                    return true;
                }
            }
            // Leading dash group: "abc-the.title..." → group "abc"
            int firstDash = part.indexOf('-');
            if (firstDash > 0 && firstDash < part.length() - 1) {
                var candidate = part.substring(0, firstDash);
                var rest = part.substring(firstDash + 1, Math.min(part.length(), Math.max(firstDash + 2, end - filepart.start())));
                if (validGroupName(candidate) && rest.contains(".") && !rest.contains(" ")) {
                    int absStart = filepart.start();
                    int absEnd = filepart.start() + firstDash;
                    var m = new Match("release_group", candidate, absStart, absEnd,
                        candidate, 1500, Set.of("scene"), false);
                    ctx.matches.add(m);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean detectScene(ParseContext ctx) {
        var input = ctx.input;
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var ext = ctx.matches.named("container")
                .filter(m -> filepart.covers(m.start(), m.end()) && m.tags().contains("extension"))
                .findFirst().orElse(null);
            int rangeEnd = ext != null ? ext.start() : filepart.end();

            // Find last "scene-prev" match within filepart and rangeEnd.
            var prev = ctx.matches.all()
                .filter(m -> SCENE_PREV.contains(m.name()))
                .filter(m -> m.start() >= filepart.start() && m.end() <= rangeEnd)
                .reduce((a, b) -> a.end() >= b.end() ? a : b)
                .orElse(null);
            if (prev == null) continue;

            // Is the gap [prev.end, rangeEnd) a single non-trivial token?
            var gap = input.substring(prev.end(), rangeEnd);
            var trimmed = gap.replaceAll("^[" + io.guessit.engine.Seps.regexCharClass() + "]+", "")
                              .replaceAll("[" + io.guessit.engine.Seps.regexCharClass() + "]+$", "");
            if (trimmed.isEmpty()) continue;
            if (!validGroupName(trimmed)) continue;
            int s = input.indexOf(trimmed, prev.end());
            if (s < 0) continue;
            int e = s + trimmed.length();
            ctx.matches.add(new Match("release_group", trimmed, s, e, trimmed, 1500, Set.of("scene"), false));
            return true;
        }
        return false;
    }

    private boolean detectAnimeBrackets(ParseContext ctx) {
        for (var marker : ctx.markers) {
            if (!"group".equals(marker.name())) continue;
            var raw = marker.raw();
            if (raw.isBlank()) continue;
            // No non-language matches inside the bracket; raw should not be just digits.
            boolean hasOtherInside = ctx.matches.all()
                .anyMatch(m -> !m.name().equals("language") && !m.name().equals("subtitle_language")
                            && marker.covers(m.start(), m.end()));
            if (hasOtherInside) continue;
            if (raw.chars().allMatch(Character::isDigit)) continue;
            ctx.matches.add(new Match("release_group", raw.trim(), marker.start(), marker.end(),
                raw, 1500, Set.of("anime"), false));
            return true;
        }
        return false;
    }

    private static boolean validGroupName(String s) {
        var t = s.trim();
        if (t.length() < 2) return false;
        if (t.contains(" ")) return false;
        if (t.chars().allMatch(Character::isDigit)) return false;
        return true;
    }
}
```

- [ ] **Step 4: Register and run**

Edit `Rules.java`:

```java
            new LanguageExtractor(),
            new CountryExtractor(),
            new StreamingServiceExtractor(),
            new SourceExtractor(),
            new ReleaseGroupExtractor()
```

Run: `mvn -q test -Dtest=ReleaseGroupExtractorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/ReleaseGroupExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/ReleaseGroupExtractorTest.java
git commit -m "feat(rules): add ReleaseGroupExtractor (expected, dash, scene, anime)"
```

---

## Task 12: Widen YML parity gate to Phase 2 properties + ≥50% threshold

**Files:**
- Modify: `src/test/java/io/guessit/parity/YmlParityTest.java`

- [ ] **Step 1: Widen `PHASE_PROPS`**

Replace the property allow-list and add a phase threshold guard. Open `src/test/java/io/guessit/parity/YmlParityTest.java`, then:

- Rename `PHASE_1_PROPS` → `PHASE_PROPS`.
- Add Phase 2 keys.

```java
    private static final Set<String> PHASE_PROPS = Set.of(
        // Phase 1
        "year", "container", "screen_size", "aspect_ratio", "frame_rate",
        "video_codec", "video_profile", "color_depth", "video_api",
        "audio_codec", "audio_profile", "audio_channels",
        // Phase 2
        "source", "other",
        "language", "subtitle_language",
        "country",
        "release_group",
        "website", "streaming_service");
```

Update the filter at the bottom of `allYmlCases()`:

```java
    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
            .filter(c -> !c.expected().isEmpty())
            .filter(c -> c.expected().keySet().stream().allMatch(PHASE_PROPS::contains));
    }
```

- [ ] **Step 2: Run YML parity suite**

Run: `mvn -q test -Dtest=YmlParityTest`
Expected: many failures initially. That's OK — Step 3 measures the rate.

- [ ] **Step 3: Compute pass rate ≥50%**

Run:

```bash
python3 - <<'EOF'
import xml.etree.ElementTree as ET
t = ET.parse("target/surefire-reports/TEST-io.guessit.parity.YmlParityTest.xml").getroot()
total = int(t.attrib["tests"])
failures = int(t.attrib.get("failures", 0)) + int(t.attrib.get("errors", 0))
skipped = int(t.attrib.get("skipped", 0))
passed = total - failures - skipped
print(f"passed={passed} failed={failures} skipped={skipped} total={total} pass_rate={passed/total:.1%}")
assert passed / total >= 0.50, f"Phase 2 pass rate {passed/total:.1%} < 50% target"
EOF
```

Expected: `pass_rate >= 50.0%` and the assertion does not fire.

- [ ] **Step 4: If pass rate < 50%, debug** (skip if Step 3 passed)

Pick the first 10 failing cases from Surefire, read each, identify which extractor needs a tweak. Add focused unit tests, fix, rerun. Land each fix as its own commit:

```bash
git commit -m "fix(rules): <extractor> handle <case>"
```

Common patterns to expect:
- Source patterns missing edge cases like `H264-Group` adjacency: tighten regex.
- Language false-positives on common words (`he`, `it`, `por`, `de`): `RemoveInvalidLanguages` equivalent — drop matches whose lowercase raw is in `advanced_config.common_words`.
- Release-group capturing screen sizes / codec letters: tighten `validGroupName`.
- `subtitle_language` rules picking up languages adjacent to non-subtitle suffixes: re-check adjacency.

If `RemoveInvalidLanguages` is needed, add to `LanguageExtractor.postProcess`:

```java
    private void dropCommonWordLanguages(ParseContext ctx) {
        @SuppressWarnings("unchecked")
        var commonWords = (List<String>) ctx.config.raw().getOrDefault("advanced_config", java.util.Map.of()) instanceof java.util.Map<?,?> ac
            ? (List<String>) ((java.util.Map<String,Object>) ac).getOrDefault("common_words", List.of())
            : List.of();
        if (commonWords.isEmpty()) return;
        var lc = new java.util.HashSet<String>();
        for (var s : commonWords) lc.add(s.toLowerCase(java.util.Locale.ROOT));
        var toRemove = new java.util.ArrayList<Match>();
        for (var name : List.of("language", "subtitle_language")) {
            for (var m : ctx.matches.named(name).toList()) {
                if (lc.contains(m.raw().toLowerCase(java.util.Locale.ROOT))) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
```

Wire it as the first step of `LanguageExtractor.postProcess`.

- [ ] **Step 5: Commit harness change**

```bash
git add src/test/java/io/guessit/parity/YmlParityTest.java
git commit -m "test(parity): enable Phase 2 YML cases (≥50%)"
```

---

## Final verification

- [ ] **Step 1: Full build + tests**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS; all unit tests green; parity ≥50%.

- [ ] **Step 2: Smoke CLI**

Run: `java -jar target/guessit-java-*-cli.jar "Show.S01E02.Pilot.DVDRip.x264-CS.mkv"`
Expected output (key order may vary):

```
season: 1
episode: 2
title: Pilot
source: DVD
other: Rip
video_codec: H.264
release_group: CS
container: mkv
```

(Note: `season`/`episode`/`title` fields land in Plans 3/4, so they may be absent in Phase 2.)

- [ ] **Step 3: Final tag commit**

```bash
git tag plan-2-done
```

---

## Self-Review

**1. Spec coverage** — Spec phase 2 lists: markers (already in Plan 0), source, release_group, language, country, ~50% YML. Coverage:
- markers: shipped in Plan 0; no further work here.
- source: Tasks 4–5 (`SourceExtractor` + `ValidateSourcePrefixSuffix` + `ValidateWeakSource` + partial `UltraHdBlurayRule`).
- other: Task 3 (`OtherExtractor` config-driven).
- website: Task 6.
- streaming_service: Task 7 (+ `ValidateStreamingService`).
- language + subtitle_language: Tasks 8–9 (`LanguageExtractor` with `SubtitlePrefixLanguageRule`, `SubtitleSuffixLanguageRule`, `SubtitleExtensionRule`, `RemoveUndeterminedLanguages`; `RemoveInvalidLanguages` via Task 12 debug step).
- country: Task 10 (with language-vs-country conflict).
- release_group: Task 11 (`expected_group`, `DashSeparatedReleaseGroup`, `SceneReleaseGroup` simplified, `AnimeReleaseGroup`).
- registration + parity ≥50%: Task 12.

**2. Placeholder scan** — No `TODO`/`TBD`. Each step contains complete code or exact commands. Task 12 Step 4 lists representative debug fixes; the `commonWords`/`RemoveInvalidLanguages` snippet is full code, not a placeholder.

**3. Type consistency** — `Match`, `MatchSet`, `RegexOpts`, `StringOpts`, `Extractor`, `ParseContext`, `Marker.covers()`, `OptionsConfig.section()`, `OptionsConfig.topLevelList()` (added in Task 2), `Validators.sepsBefore/After/Surround`, `Abbreviations.dash`, `Words.iter` referenced across tasks all match either the existing Plan-0 signatures or the signatures introduced in this plan (verified by reading each existing file before drafting).

**4. Deferred vs. shipped** — Explicitly deferred to later plans, with code comments where applicable:
- `UltraHdBlurayRule` `other:Ultra HD` branch (needs the `Ultra HD` value from `other`'s expanded set) — shipped only the `screen_size:2160p` branch in Task 5.
- `PreferTitleOverWebsite` — needs `title` (Plan 4); shipped only the basic website detection.
- Full `LanguageWord.extended_word` merging (`pt-BR`, `soft subs`) — Plan 5 polish.
- `SceneReleaseGroup` strict variant (anchored on `title`) and `RemoveInvalidLanguages` blacklist beyond the `common_words` set — covered partially in Task 12 debug; full Python parity in Plan 5 polish.
- `KeepMarkedYearInFilepart` season-vs-year cases — already covered for groups in Plan 1; year-vs-season swap belongs to Plan 3.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-02-plan-2-phase2-source-language-country-releasegroup.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.
