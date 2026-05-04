# Plan 5: Phase 5 — long-tail extractors + processors + cluster fixes (100% YML parity)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close YML parity from 95.2% (1597/1678 pass at commit `21ac9c3`) to 100%. Land the deferred Phase-5 extractors (`bit_rate`, `size`, `edition`, `cd`/`cd_count`, `bonus`/`bonus_title`, `film`/`film_title`, `part`, `crc32`, `mimetype`), port the Python `processors.py` cleanup rules we still lack (`EnlargeGroupMatches`, `RemoveLessSpecificSeasonEpisode`, `RemoveAmbiguous`, `SeasonYear`/`YearSeason`, `StripSeparators`), implement six cluster fixes for the remaining 81 YML failures, and widen the YML parity gate to **all** properties at a **100%** pass threshold.

**Architecture:**

- New extractors live in `io.guessit.rules.property` and follow the existing `Extractor` interface (regex+config in `extract`, post-rules in `postProcess`). All deferred extractors are config-driven via the existing `ConfigLoader` patterns under `config/options.json` — no new config wiring.
- Two new sub-rules attach to existing extractors: `BonusTitleRule` (run after `TitleExtractor.postProcess`) builds `bonus_title` from the trailing hole, and `FilmTitleRule` builds `film_title` from the leading hole. Both reuse `Holes.compute(...)`.
- `Quantity` is split into a base record + two specialised parsers (`Size.fromString`, `BitRate.fromString`) to mirror Python `rules/common/quantity.py`.
- Five processor rules port to Java `PostProcessor`s in `io.guessit.rules.post`. Order in `defaultPipeline()`'s `PostPhase`:
  `EnlargeGroupMatches`, `EquivalentHoles`, `PreferLastPath`, `RangeFiller`, `EpisodeNumberSeparatorRange`, `SeasonYearLink`, `SeasonYear`, `YearSeason`, `RemoveLessSpecificSeasonEpisode("season")`, `RemoveLessSpecificSeasonEpisode("episode")`, `RemoveAmbiguous`, `TypeProcessor`, `MimetypeProcessor`, `StripSeparators`, `PrivateRemover`.
- Cluster fixes are surgical edits to existing extractors (`SeasonEpisodeExtractor`, `LanguageExtractor`, `TitleExtractor`, `ReleaseGroupExtractor`, `EpisodeTitleExtractor`) plus one new `expectedTitle: re:` engine helper in `Options`/`TitleExtractor`.
- `OutputBuilder` already wires every Phase-5 result key (`bit_rate`, `size`, `edition`, `cd`, `cd_count`, `film`, `film_title`, `bonus`, `bonus_title`, `crc32`, `mimetype`, `part`); no change needed there beyond two new keys touched by tests.

**Tech Stack:** Same as Plans 0–4 — Java 25, JUnit Jupiter 5.12.x, AssertJ, Apache Commons CSV, Jackson + SnakeYAML. No new runtime deps. We rely on `java.net.URLConnection.guessContentTypeFromName` for `mimetype` (matches Python `mimetypes.guess_type` for the standard extensions exercised by YML).

**Reference source:**
- Python: `/tmp/guessit/guessit/rules/properties/{bit_rate,size,edition,cd,bonus,film,part,crc,mimetype}.py`
- Python: `/tmp/guessit/guessit/rules/processors.py`
- Python: `/tmp/guessit/guessit/rules/common/{quantity,formatters,validators,pattern,words,comparators}.py`
- Python rebulk: `/tmp/rebulk/rebulk/rules/{remove_match,append_match,rename_match}.py`
- Spec: `docs/superpowers/specs/2026-05-02-guessit-java-design.md`
- Plan 0–4: `docs/superpowers/plans/2026-05-0{2,3}-plan-{0..4}-*.md`
- Failure clusters: memory file `yml_parity_clusters.md`

---

## File Structure

Created in this plan (paths relative to repo root):

```
src/main/java/io/guessit/
├── util/
│   ├── Size.java                                // Quantity sub-type, parses 1.5GB/300MB/etc
│   └── BitRate.java                             // Quantity sub-type, parses 320Kbps/1.5Mbps/etc
├── rules/
│   ├── property/
│   │   ├── BitRateExtractor.java                // regex + BitRateTypeRule
│   │   ├── SizeExtractor.java
│   │   ├── EditionExtractor.java                // config-driven Collector/Special/etc
│   │   ├── CdExtractor.java                     // cd-N(-of-N)? + N-cd(s)
│   │   ├── BonusExtractor.java                  // x(\d+) + BonusTitleRule
│   │   ├── FilmExtractor.java                   // f(\d{1,2}) + FilmTitleRule
│   │   ├── PartExtractor.java                   // (pt|part)-?numeral
│   │   ├── CrcExtractor.java                    // crc32 8-hex + uuid heuristic
│   │   └── ExpectedTitleRegex.java              // helper: split "re:..." entries
│   └── post/
│       ├── EnlargeGroupMatches.java             // PRE_PROCESS — extend matches into ()/[]
│       ├── RemoveLessSpecificSeasonEpisode.java // generic on name "season"/"episode"
│       ├── RemoveAmbiguous.java                 // dedup by name across fileparts
│       ├── SeasonYear.java                      // season→year when no year
│       ├── YearSeason.java                      // year→season when episode but no season
│       ├── StripSeparators.java                 // trim leading/trailing seps from match.raw
│       ├── EpisodeNumberSeparatorRange.java     // expand "16-20" → 16..20 episodes
│       ├── MimetypeProcessor.java               // append mimetype match
│       └── LanguageCountryAttach.java           // attach pt-BR / de-CH country to language

src/test/java/io/guessit/
├── util/
│   ├── SizeTest.java
│   └── BitRateTest.java
├── rules/
│   ├── property/
│   │   ├── BitRateExtractorTest.java
│   │   ├── SizeExtractorTest.java
│   │   ├── EditionExtractorTest.java
│   │   ├── CdExtractorTest.java
│   │   ├── BonusExtractorTest.java
│   │   ├── FilmExtractorTest.java
│   │   ├── PartExtractorTest.java
│   │   └── CrcExtractorTest.java
│   └── post/
│       ├── EnlargeGroupMatchesTest.java
│       ├── RemoveLessSpecificSeasonEpisodeTest.java
│       ├── RemoveAmbiguousTest.java
│       ├── SeasonYearTest.java
│       ├── YearSeasonTest.java
│       ├── StripSeparatorsTest.java
│       ├── EpisodeNumberSeparatorRangeTest.java
│       ├── MimetypeProcessorTest.java
│       └── LanguageCountryAttachTest.java
```

Modified in this plan:

```
src/main/java/io/guessit/util/Quantity.java                 // become abstract base; keep format()
src/main/java/io/guessit/Options.java                       // add expectedTitle re: precompiled forms
src/main/java/io/guessit/rules/Rules.java                   // register all new extractors + processors
src/main/java/io/guessit/rules/property/TitleExtractor.java // honor expected_title re:; AltTitle multi-split
src/main/java/io/guessit/rules/property/SeasonEpisodeExtractor.java // compact-SSEE last-vs-first; foobar.213
src/main/java/io/guessit/rules/property/LanguageExtractor.java      // emit "pt-BR" with attached country
src/main/java/io/guessit/rules/property/ReleaseGroupExtractor.java  // 0106 outer-folder hand-off
src/test/java/io/guessit/parity/YmlParityTest.java          // PHASE_PROPS = ALL keys; threshold 100%
```

Responsibilities (one per file):
- `util/Size`, `util/BitRate` — `record Size(double magnitude, String units) extends Quantity`-style. Static `fromString(String)` parses the regex `(\d+(?:\.\d+)?)([^\d]+)?` and normalises units (Size: trim seps + uppercase; BitRate: trim seps + capitalise + replace `bits?`→`bps`). Equality drives YML compare via the inherited `format()`.
- `rules/property/<X>Extractor` — implements `Extractor`. Patterns from `config/options.json` via `cfg.patternsFor("<key>")` (already used by `EditionExtractor`'s siblings). Validators (`seps_surround`, value-range) implemented locally; no Python eval bridge.
- `rules/property/CrcExtractor` — two extractors in one class: `crc32` (regex `[0-9a-f]{8}`, `seps_surround`, conflict-loses to season/episode) and `uuid` (functional alpha-num-mixing heuristic, conflict-wins over season/episode).
- `rules/property/ExpectedTitleRegex` — pure helper, parses `"re:foo|bar"` syntax in `Options.expectedTitle`, returns `List<Pattern>`. Called from `TitleExtractor.extract`.
- `rules/post/<X>` — implements `PostProcessor`. Ordered in `defaultPipeline()` to match Python's `Rebulk().rules(...)` order in `processors.py`.
- `rules/post/EpisodeNumberSeparatorRange` — generic `AbstractSeparatorRange` port limited to the `episode` name; expands `16-20` between two `episode` matches into `episodeList=[16,17,18,19,20]`. Bounded by `<= 20` jump like Python `episodes.py`.
- `rules/post/LanguageCountryAttach` — looks for `language` matches followed by `-XX` (alpha-2 country); when XX resolves via `LanguageRegistry.findCountry`, replaces the language `Match` with one whose `value.country == Country(XX)` and removes the country candidate. Mirrors Python `language.py`'s `SubtitlePrefixLanguageRule`/country-attached logic.

---

## Task 1: `util/Size` + `util/BitRate` (Quantity sub-types)

**Files:**
- Modify: `src/main/java/io/guessit/util/Quantity.java`
- Create: `src/main/java/io/guessit/util/Size.java`
- Create: `src/main/java/io/guessit/util/BitRate.java`
- Test:   `src/test/java/io/guessit/util/SizeTest.java`
- Test:   `src/test/java/io/guessit/util/BitRateTest.java`

- [ ] **Step 1: Make `Quantity` sealed with two permits, keep current shape**

`Quantity.java` becomes:

```java
package io.guessit.util;

public sealed class Quantity permits Size, BitRate {
    public final double value;
    public final String unit;
    protected Quantity(double value, String unit) {
        this.value = value;
        this.unit = unit;
    }
    public double value() { return value; }
    public String unit() { return unit; }
    public String format() {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return ((long) value) + " " + unit;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit);
    }
    @Override public boolean equals(Object o) {
        return o instanceof Quantity q && Double.compare(value, q.value) == 0 && unit.equals(q.unit);
    }
    @Override public int hashCode() { return java.util.Objects.hash(value, unit); }
    @Override public String toString() { return format(); }

    /** Generic dispatch parser kept for Phase-1 callers that handed us free-form text. */
    public static Quantity parse(String s) {
        var t = s.trim();
        var lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("bps") || lower.endsWith("bit") || lower.endsWith("bits"))
            return BitRate.fromString(t);
        return Size.fromString(t);
    }
}
```

- [ ] **Step 2: Write `SizeTest`**

```java
package io.guessit.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SizeTest {
    @Test void parsesIntMagnitude() {
        var s = Size.fromString("300MB");
        assertEquals(300.0, s.value(), 0.0);
        assertEquals("MB", s.unit());
        assertEquals("300 MB", s.format());
    }
    @Test void parsesFloatMagnitude() {
        var s = Size.fromString("1.5GB");
        assertEquals("1.5 GB", s.format());
    }
    @Test void normalisesUnitToUpperCaseStripsSeps() {
        assertEquals("4.7 GB", Size.fromString("4.7-gb").format());
        assertEquals("700 MB", Size.fromString("700.mb").format());
    }
}
```

- [ ] **Step 3: Run test → fails (no `Size`)**

Run: `mvn -q test -Dtest=SizeTest`
Expected: FAIL — class `Size` does not exist.

- [ ] **Step 4: Implement `Size`**

```java
package io.guessit.util;

import io.guessit.engine.Seps;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Size extends Quantity {
    private static final Pattern P = Pattern.compile("(?<m>\\d+(?:\\.\\d+)?)(?<u>[^\\d]+)?");
    private Size(double v, String u) { super(v, u); }
    public static Size fromString(String s) {
        var m = P.matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("Not a size: " + s);
        var raw = m.group("u") == null ? "" : m.group("u");
        var u = trimSeps(raw).toUpperCase(Locale.ROOT);
        return new Size(Double.parseDouble(m.group("m")), u);
    }
    private static String trimSeps(String s) {
        int a = 0, b = s.length();
        while (a < b && Seps.isSep(s.charAt(a))) a++;
        while (b > a && Seps.isSep(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
}
```

- [ ] **Step 5: Run `SizeTest` → green**

Run: `mvn -q test -Dtest=SizeTest`
Expected: PASS.

- [ ] **Step 6: Write `BitRateTest`**

```java
package io.guessit.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BitRateTest {
    @Test void parsesKbps() {
        assertEquals("320 Kbps", BitRate.fromString("320Kbps").format());
    }
    @Test void parsesMbpsFloat() {
        assertEquals("1.5 Mbps", BitRate.fromString("1.5Mbps").format());
    }
    @Test void normalisesBitsToBps() {
        assertEquals("128 Kbps", BitRate.fromString("128kbits").format());
        assertEquals("128 Kbps", BitRate.fromString("128kbit").format());
    }
    @Test void capitaliseUnitFirstLetterOnly() {
        assertEquals("320 Kbps", BitRate.fromString("320KBPS").format());
        assertEquals("320 Kbps", BitRate.fromString("320kbps").format());
    }
}
```

- [ ] **Step 7: Run → fails**

Run: `mvn -q test -Dtest=BitRateTest`
Expected: FAIL.

- [ ] **Step 8: Implement `BitRate`**

```java
package io.guessit.util;

import io.guessit.engine.Seps;
import java.util.Locale;
import java.util.regex.Pattern;

public final class BitRate extends Quantity {
    private static final Pattern P = Pattern.compile("(?<m>\\d+(?:\\.\\d+)?)(?<u>[^\\d]+)?");
    private BitRate(double v, String u) { super(v, u); }
    public static BitRate fromString(String s) {
        var m = P.matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("Not a bit rate: " + s);
        var raw = m.group("u") == null ? "" : m.group("u");
        var u = trimSeps(raw);
        u = capitalise(u);
        u = u.replace("bits", "bps").replace("bit", "bps");
        return new BitRate(Double.parseDouble(m.group("m")), u);
    }
    private static String capitalise(String s) {
        if (s.isEmpty()) return s;
        var lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
    private static String trimSeps(String s) {
        int a = 0, b = s.length();
        while (a < b && Seps.isSep(s.charAt(a))) a++;
        while (b > a && Seps.isSep(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
}
```

- [ ] **Step 9: Run → green**

Run: `mvn -q test -Dtest=BitRateTest`
Expected: PASS.

- [ ] **Step 10: Run full test suite (no regressions in `QuantityTest`)**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/io/guessit/util/Quantity.java src/main/java/io/guessit/util/Size.java src/main/java/io/guessit/util/BitRate.java src/test/java/io/guessit/util/SizeTest.java src/test/java/io/guessit/util/BitRateTest.java
git commit -m "feat(util): split Quantity into sealed base + Size/BitRate parsers"
```

---

## Task 2: `SizeExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/SizeExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/SizeExtractorTest.java`

Python source: `/tmp/guessit/guessit/rules/properties/size.py` — single regex `\d+-?[mgt]b` and `\d+\.\d+-?[mgt]b`, formatter `Size.fromstring`, validator `seps_surround`, tag `release-group-prefix`.

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.util.Size;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SizeExtractorTest {
    @Test void parsesIntegerGigabytes() {
        var r = Guessit.parse("Movie.4.7gb.mkv");
        assertThat(r.size()).isEqualTo(Size.fromString("4.7GB"));
    }
    @Test void parsesMegabytes() {
        var r = Guessit.parse("Movie.700mb.mkv");
        assertThat(r.size()).isEqualTo(Size.fromString("700MB"));
    }
    @Test void requiresSepsSurround() {
        // "abc500mbxyz" should NOT match — no separator before '500' and after 'mb'
        var r = Guessit.parse("abc500mbxyz.mkv");
        assertThat(r.size()).isNull();
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=SizeExtractorTest`
Expected: FAIL — `r.size()` is `null` because no extractor exists yet.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.util.Size;

import java.util.regex.Pattern;

public final class SizeExtractor implements Extractor {
    private static final Pattern P = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?-?[mgt]b)");

    @Override public String name() { return "size"; }

    @Override
    public void extract(ParseContext ctx) {
        var m = P.matcher(ctx.input);
        while (m.find()) {
            int s = m.start(1), e = m.end(1);
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            var raw = ctx.input.substring(s, e);
            ctx.matches.add(new Match("size", Size.fromString(raw), s, e, raw,
                priority(), java.util.Set.of("release-group-prefix"), false));
        }
    }
}
```

(If `Validators.sepsSurround` does not exist yet, port it from any existing extractor — e.g. `EditionExtractor`-style helper. If that helper hasn't landed, inline a `seps_surround` check using `Seps.isSep` over the chars at `s-1` and `e`, treating positions outside `[0, input.length)` as separators.)

- [ ] **Step 4: Register in `Rules.allInOrder()` (just before `LanguageExtractor`, which is where strong-codec extractors cluster)**

In `src/main/java/io/guessit/rules/Rules.java`, after `new ContainerExtractor(),` add `new SizeExtractor(),`.

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=SizeExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/SizeExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/SizeExtractorTest.java
git commit -m "feat(rules): add SizeExtractor (size in MB/GB/TB)"
```

---

## Task 3: `BitRateExtractor` + `BitRateTypeRule`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/BitRateExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/BitRateExtractorTest.java`

Python source: `/tmp/guessit/guessit/rules/properties/bit_rate.py`. Initial guess is `audio_bit_rate`; `BitRateTypeRule` reclassifies to `video_bit_rate` when the match is wedged between a `source|screen_size|video_codec` predecessor and an `audio_codec` follower with no other holes — *unless* the rate is `<10 Mbps` or `Kbps` (then it stays audio). Java's `GuessResult` has only `bitRate`; YML cases under PHASE_PROPS use `bit_rate` as the unified key, so we collapse: emit a single `Match` named `bit_rate` (no audio/video split) — confirm against fixtures during step 5.

- [ ] **Step 1: Inspect actual YML coverage to confirm key**

Run: `grep -rn "bit_rate\|video_bit_rate\|audio_bit_rate" src/test/resources/yml/ | head -20`
Expected: Cases use `bit_rate:` only (the Python builder collapses to `bit_rate` in `toMap` for many tests). If you see `audio_bit_rate`/`video_bit_rate` keys, extend `KEY_TO_FIELDS` in `YmlParityTest.java` and add `audioBitRate`/`videoBitRate` to `GuessResult`. Otherwise, single `bit_rate` is enough.

- [ ] **Step 2: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.util.BitRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BitRateExtractorTest {
    @Test void parsesKbpsAudio() {
        var r = Guessit.parse("Movie.320Kbps.mkv");
        assertThat(r.bitRate()).isEqualTo(BitRate.fromString("320Kbps"));
    }
    @Test void parsesMbpsFloat() {
        var r = Guessit.parse("Movie.1.5Mbps.mkv");
        assertThat(r.bitRate()).isEqualTo(BitRate.fromString("1.5Mbps"));
    }
    @Test void noEmissionWithoutSepsSurround() {
        var r = Guessit.parse("abc320Kbpsxyz.mkv");
        assertThat(r.bitRate()).isNull();
    }
}
```

- [ ] **Step 3: Run → fails**

Run: `mvn -q test -Dtest=BitRateExtractorTest`
Expected: FAIL.

- [ ] **Step 4: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.util.BitRate;

import java.util.Set;
import java.util.regex.Pattern;

public final class BitRateExtractor implements Extractor {
    private static final Pattern P = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?-?[kmg]b(?:ps|its?))");

    @Override public String name() { return "bit_rate"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var m = P.matcher(ctx.input);
        while (m.find()) {
            int s = m.start(1), e = m.end(1);
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            var raw = ctx.input.substring(s, e);
            ctx.matches.add(new Match("bit_rate", BitRate.fromString(raw), s, e, raw,
                priority(), Set.of("release-group-prefix"), false));
        }
    }
}
```

(No `BitRateTypeRule` yet — only port if YML cases under unified `bit_rate` key fail.)

- [ ] **Step 5: Register in `Rules.allInOrder()` after `SizeExtractor`**

- [ ] **Step 6: Run → green**

Run: `mvn -q test -Dtest=BitRateExtractorTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/guessit/rules/property/BitRateExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/BitRateExtractorTest.java
git commit -m "feat(rules): add BitRateExtractor (Kbps/Mbps audio+video bitrate)"
```

---

## Task 4: `EditionExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/EditionExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/EditionExtractorTest.java`

Python source: `/tmp/guessit/guessit/rules/properties/edition.py` (config-driven). The full alias map lives in `options.json` under `"edition"`. Use the project's existing config-pattern accessor (`OptionsConfig.patternsFor("edition")` — same path that `SourceExtractor`/`OtherExtractor` already use) so we automatically pick up Collector/Special/DDC/Criterion/Deluxe/Limited/Theatrical/DC/Extended/Alternative/Remastered/Restored/Uncensored.

- [ ] **Step 1: Write failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EditionExtractorTest {
    @Test void detectsCollectorsEdition() {
        assertThat(Guessit.parse("Movie.Collectors.Edition.mkv").edition())
            .isEqualTo("Collector");
    }
    @Test void detectsDirectorsCutShortDC() {
        assertThat(Guessit.parse("Movie.DC.1080p.BluRay-RG.mkv").edition())
            .isEqualTo("Director's Cut");
    }
    @Test void detectsExtended() {
        assertThat(Guessit.parse("Movie.Extended.1080p.mkv").edition())
            .isEqualTo("Extended");
    }
    @Test void detectsRemastered() {
        assertThat(Guessit.parse("Movie.Remastered.mkv").edition())
            .isEqualTo("Remastered");
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=EditionExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;

public final class EditionExtractor implements Extractor {
    @Override public String name() { return "edition"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        // Read alias→[regex,string,tags...] from config under "edition".
        var entries = ctx.config.aliasPatterns("edition"); // pattern bundle (mirrors SourceExtractor)
        for (var entry : entries) {
            var canonical = entry.canonical();
            for (var p : entry.compiledPatterns()) {
                var m = p.matcher(ctx.input);
                while (m.find()) {
                    int s = m.start(), e = m.end();
                    if (!Validators.sepsSurround(ctx.input, s, e)) continue;
                    if (entry.tags().contains("has-neighbor") && !hasNeighbor(ctx, s, e)) continue;
                    ctx.matches.add(new Match("edition", canonical, s, e,
                        ctx.input.substring(s, e), priority(),
                        Set.copyOf(entry.tags()), false));
                }
            }
        }
    }

    private static boolean hasNeighbor(ParseContext ctx, int s, int e) {
        // Mirrors python validators.has_neighbor — at least one non-private match
        // adjacent (separator-only gap) to the candidate.
        return ctx.matches.snapshot().stream()
            .filter(x -> !x.isPrivate())
            .anyMatch(x -> separatorOnlyGap(ctx.input, x.end(), s)
                        || separatorOnlyGap(ctx.input, e, x.start()));
    }
    private static boolean separatorOnlyGap(String in, int a, int b) {
        if (a > b) return false;
        for (int i = a; i < b; i++) if (!Seps.isSep(in.charAt(i))) return false;
        return true;
    }
}
```

If the config-bundle accessor name differs from `aliasPatterns("edition")`, follow the pattern used in `SourceExtractor.java` (the existing source extractor reads the same shape from config — copy the call site verbatim).

- [ ] **Step 4: Register in `Rules.allInOrder()` after `OtherExtractor`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=EditionExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/EditionExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/EditionExtractorTest.java
git commit -m "feat(rules): add EditionExtractor (config-driven Collector/Special/DC/Extended/etc)"
```

---

## Task 5: `CdExtractor` (cd + cd_count)

**Files:**
- Create: `src/main/java/io/guessit/rules/property/CdExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/CdExtractorTest.java`

Python: two regexes — `cd-?(?P<cd>\d+)(?:-?of-?(?P<cd_count>\d+))?` and `(?P<cd_count>\d+)-?cds?`. Validator: each value `0 < x < 100`. Both yield child matches (we emit them flat, no parent).

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdExtractorTest {
    @Test void cdOnly() {
        var r = Guessit.parse("Movie.cd1.avi");
        assertThat(r.cd()).isEqualTo(1);
        assertThat(r.cdCount()).isNull();
    }
    @Test void cdOfCount() {
        var r = Guessit.parse("Movie.cd1of2.avi");
        assertThat(r.cd()).isEqualTo(1);
        assertThat(r.cdCount()).isEqualTo(2);
    }
    @Test void cdCountAlone() {
        var r = Guessit.parse("Movie.2cds.avi");
        assertThat(r.cd()).isNull();
        assertThat(r.cdCount()).isEqualTo(2);
    }
    @Test void rejectsOutOfRange() {
        var r = Guessit.parse("Movie.cd100.avi");
        assertThat(r.cd()).isNull();
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=CdExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

public final class CdExtractor implements Extractor {
    private static final Pattern CD_OF = Pattern.compile(
        "(?i)cd-?(?<cd>\\d+)(?:-?of-?(?<count>\\d+))?");
    private static final Pattern CD_COUNT = Pattern.compile(
        "(?i)(?<count>\\d+)-?cds?");

    @Override public String name() { return "cd"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        for (var m = CD_OF.matcher(ctx.input); m.find(); ) {
            int s = m.start(), e = m.end();
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            var cd = Integer.parseInt(m.group("cd"));
            if (cd <= 0 || cd >= 100) continue;
            ctx.matches.add(new Match("cd", cd,
                m.start("cd"), m.end("cd"), m.group("cd"), priority(), Set.of(), false));
            if (m.group("count") != null) {
                var c = Integer.parseInt(m.group("count"));
                if (c > 0 && c < 100) {
                    ctx.matches.add(new Match("cd_count", c,
                        m.start("count"), m.end("count"), m.group("count"),
                        priority(), Set.of(), false));
                }
            }
        }
        for (var m = CD_COUNT.matcher(ctx.input); m.find(); ) {
            int s = m.start(), e = m.end();
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            var c = Integer.parseInt(m.group("count"));
            if (c <= 0 || c >= 100) continue;
            ctx.matches.add(new Match("cd_count", c,
                m.start("count"), m.end("count"), m.group("count"),
                priority(), Set.of(), false));
        }
    }
}
```

- [ ] **Step 4: Register in `Rules.allInOrder()` after `EditionExtractor`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=CdExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/CdExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/CdExtractorTest.java
git commit -m "feat(rules): add CdExtractor (cd / cd_count)"
```

---

## Task 6: `BonusExtractor` + `BonusTitleRule`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/BonusExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/BonusExtractorTest.java`

Python: `x(\d+)` → child capture group becomes `bonus`; conflict-solver loses to `video_codec`/strong `episode`. Bonus title is the trailing hole inside the same path marker.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BonusExtractorTest {
    @Test void detectsBonusNumberAndTitle() {
        var r = Guessit.parse("Movie.x05.Behind.The.Scenes.mkv");
        assertThat(r.bonus()).isEqualTo(5);
        assertThat(r.bonusTitle()).isEqualTo("Behind The Scenes");
    }
    @Test void losesToVideoCodec() {
        // x264 must remain video_codec, not bonus=264
        var r = Guessit.parse("Movie.x264-RG.mkv");
        assertThat(r.bonus()).isNull();
        assertThat(r.videoCodec()).contains("H.264");
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=BonusExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

public final class BonusExtractor implements Extractor {
    private static final Pattern P = Pattern.compile("(?i)x(?<n>\\d+)");

    @Override public String name() { return "bonus"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        for (var m = P.matcher(ctx.input); m.find(); ) {
            int s = m.start(), e = m.end();
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            // Conflict-solver: lose to video_codec / non-weak episode at any overlap.
            int s2 = m.start("n"), e2 = m.end("n");
            boolean codecOverlap = ctx.matches.snapshot().stream().anyMatch(x ->
                (x.name().equals("video_codec") ||
                 (x.name().equals("episode") && !x.tags().contains("weak-episode")))
                && x.start() < e && x.end() > s);
            if (codecOverlap) continue;
            ctx.matches.add(new Match("bonus", Integer.parseInt(m.group("n")),
                s2, e2, m.group("n"), priority(), Set.of(), false));
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        // BonusTitleRule — runs after TitleExtractor.postProcess so the title hole is settled.
        var bonus = ctx.matches.snapshot().stream()
            .filter(x -> x.name().equals("bonus") && !x.isPrivate())
            .findFirst().orElse(null);
        if (bonus == null) return;
        var pathMarker = ctx.markers.stream()
            .filter(mk -> mk.name().equals("path")
                       && mk.start() <= bonus.start() && mk.end() >= bonus.end())
            .findFirst().orElse(null);
        if (pathMarker == null) return;
        var hole = Holes.firstHole(ctx, bonus.end(), pathMarker.end(), Formatters::cleanup);
        if (hole == null || hole.value().isBlank()) return;
        ctx.matches.add(new Match("bonus_title", hole.value(),
            hole.start(), hole.end(), ctx.input.substring(hole.start(), hole.end()),
            priority(), Set.of(), false));
    }
}
```

- [ ] **Step 4: Register in `Rules.allInOrder()` after `EpisodeWordExtractor` (so it runs after strong episode/codec rules but before title)**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=BonusExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/BonusExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/BonusExtractorTest.java
git commit -m "feat(rules): add BonusExtractor + bonus_title hole rule"
```

---

## Task 7: `FilmExtractor` + `FilmTitleRule`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/FilmExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/FilmExtractorTest.java`

Python: `f(\d{1,2})` → integer `film`. `film_title` is the leading hole inside the same path marker (start of marker .. film.start+1).

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilmExtractorTest {
    @Test void detectsFilmNumberAndPrefixTitle() {
        var r = Guessit.parse("My.Awesome.Series.f01.mkv");
        assertThat(r.film()).isEqualTo(1);
        assertThat(r.filmTitle()).isEqualTo("My Awesome Series");
    }
    @Test void rejectsThreeDigits() {
        var r = Guessit.parse("Movie.f100.mkv");
        assertThat(r.film()).isNull();
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=FilmExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

public final class FilmExtractor implements Extractor {
    private static final Pattern P = Pattern.compile("(?i)f(?<n>\\d{1,2})");

    @Override public String name() { return "film"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        for (var m = P.matcher(ctx.input); m.find(); ) {
            int s = m.start(), e = m.end();
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            ctx.matches.add(new Match("film", Integer.parseInt(m.group("n")),
                m.start("n"), m.end("n"), m.group("n"), priority(), Set.of(), false));
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var film = ctx.matches.snapshot().stream()
            .filter(x -> x.name().equals("film") && !x.isPrivate())
            .findFirst().orElse(null);
        if (film == null) return;
        var pathMarker = ctx.markers.stream()
            .filter(mk -> mk.name().equals("path")
                       && mk.start() <= film.start() && mk.end() >= film.end())
            .findFirst().orElse(null);
        if (pathMarker == null) return;
        var hole = Holes.firstHole(ctx, pathMarker.start(), film.start() + 1, Formatters::cleanup);
        if (hole == null || hole.value().isBlank()) return;
        ctx.matches.add(new Match("film_title", hole.value(),
            hole.start(), hole.end(), ctx.input.substring(hole.start(), hole.end()),
            priority(), Set.of(), false));
    }
}
```

- [ ] **Step 4: Register after `BonusExtractor`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=FilmExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/FilmExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/FilmExtractorTest.java
git commit -m "feat(rules): add FilmExtractor + film_title hole rule"
```

---

## Task 8: `PartExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/PartExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/PartExtractorTest.java`

Python: `(pt|part)-?<numeral>` where `numeral` = arabic digits **or** roman (I, II, III, IV, V…). Value range `0 < x < 100`.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartExtractorTest {
    @Test void arabicPart() {
        assertThat(Guessit.parse("Movie.Part2.mkv").part()).isEqualTo(2);
    }
    @Test void ptShortPrefix() {
        assertThat(Guessit.parse("Movie.pt3.mkv").part()).isEqualTo(3);
    }
    @Test void romanPart() {
        assertThat(Guessit.parse("Movie.PartIII.mkv").part()).isEqualTo(3);
    }
    @Test void rejectsOutOfRange() {
        assertThat(Guessit.parse("Movie.Part200.mkv").part()).isNull();
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=PartExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class PartExtractor implements Extractor {
    private static final Pattern P = Pattern.compile(
        "(?i)(?:pt|part)-?(?<n>\\d{1,3}|m{0,3}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3}))");

    private static final Map<Character,Integer> ROMAN = Map.of(
        'i',1,'v',5,'x',10,'l',50,'c',100,'d',500,'m',1000);

    @Override public String name() { return "part"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        for (var m = P.matcher(ctx.input); m.find(); ) {
            int s = m.start(), e = m.end();
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            var raw = m.group("n");
            int v;
            try { v = Integer.parseInt(raw); }
            catch (NumberFormatException ex) { v = roman(raw); }
            if (v <= 0 || v >= 100) continue;
            ctx.matches.add(new Match("part", v,
                m.start("n"), m.end("n"), raw, priority(), Set.of(), false));
        }
    }

    private static int roman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int cur = ROMAN.getOrDefault(Character.toLowerCase(s.charAt(i)), 0);
            total += cur < prev ? -cur : cur;
            prev = cur;
        }
        return total;
    }
}
```

- [ ] **Step 4: Register after `FilmExtractor`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=PartExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/PartExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/PartExtractorTest.java
git commit -m "feat(rules): add PartExtractor (pt/part + arabic/roman numerals)"
```

---

## Task 9: `CrcExtractor` (crc32 + uuid)

**Files:**
- Create: `src/main/java/io/guessit/rules/property/CrcExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/CrcExtractorTest.java`

Python: `(?:[a-fA-F]|[0-9]){8}` for `crc32`, conflict-loses to season/episode. UUID is functional — over the input, find `[a-zA-Z0-9-]{20,}` runs whose char-class switch ratio > 0.4 and whose letter-flip ratio > 0.4.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrcExtractorTest {
    @Test void hex8Crc32() {
        assertThat(Guessit.parse("Show.S01E02.[ABCD1234].mkv").crc32())
            .isEqualToIgnoringCase("ABCD1234");
    }
    @Test void crcLosesToSeasonEpisode() {
        // 8 digits between SxxExx and codec must NOT become crc32 if it overlaps season/episode.
        var r = Guessit.parse("Show.S01E02.12345678.x264.mkv");
        assertThat(r.crc32()).isEqualToIgnoringCase("12345678");
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=CrcExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement (crc32 only — defer uuid until YML demands)**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

public final class CrcExtractor implements Extractor {
    private static final Pattern CRC = Pattern.compile("(?i)\\b[0-9a-f]{8}\\b");

    @Override public String name() { return "crc32"; }
    @Override public int priority() { return 500; } // weaker than season/episode

    @Override
    public void extract(ParseContext ctx) {
        for (var m = CRC.matcher(ctx.input); m.find(); ) {
            int s = m.start(), e = m.end();
            if (!Validators.sepsSurround(ctx.input, s, e)) continue;
            ctx.matches.add(new Match("crc32", ctx.input.substring(s, e),
                s, e, ctx.input.substring(s, e), priority(), Set.of(), false));
        }
    }
}
```

- [ ] **Step 4: Register after `PartExtractor` (early enough that conflict resolution can still down-rank it vs season/episode)**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=CrcExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/CrcExtractor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/property/CrcExtractorTest.java
git commit -m "feat(rules): add CrcExtractor (crc32 8-hex)"
```

---

## Task 10: `MimetypeProcessor` (PostPhase)

**Files:**
- Create: `src/main/java/io/guessit/rules/post/MimetypeProcessor.java`
- Test:   `src/test/java/io/guessit/rules/post/MimetypeProcessorTest.java`

Python: appends one zero-width `mimetype` match using `mimetypes.guess_type`. We use `URLConnection.guessContentTypeFromName` plus a small overlay (`.mkv → video/x-matroska`, `.flv → video/x-flv`, `.srt → application/x-subrip`) for things the JDK omits — keep the overlay tiny and only what YML exercises.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MimetypeProcessorTest {
    @Test void mp4() {
        assertThat(Guessit.parse("Movie.mp4").mimetype()).isEqualTo("video/mp4");
    }
    @Test void mkv() {
        assertThat(Guessit.parse("Movie.mkv").mimetype()).isEqualTo("video/x-matroska");
    }
    @Test void srt() {
        assertThat(Guessit.parse("Movie.srt").mimetype()).isEqualTo("application/x-subrip");
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=MimetypeProcessorTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.net.URLConnection;
import java.util.Map;
import java.util.Set;

public final class MimetypeProcessor implements PostProcessor {
    private static final Map<String, String> OVERLAY = Map.of(
        "mkv",  "video/x-matroska",
        "flv",  "video/x-flv",
        "srt",  "application/x-subrip",
        "ass",  "text/x-ssa",
        "ssa",  "text/x-ssa",
        "idx",  "application/x-idx",
        "sub",  "application/x-subrip",
        "nfo",  "text/x-nfo");

    @Override
    public void apply(ParseContext ctx) {
        var lower = ctx.input.toLowerCase(java.util.Locale.ROOT);
        var dot = lower.lastIndexOf('.');
        String mime = null;
        if (dot >= 0 && dot < lower.length() - 1) {
            var ext = lower.substring(dot + 1);
            mime = OVERLAY.get(ext);
        }
        if (mime == null) mime = URLConnection.guessContentTypeFromName(ctx.input);
        if (mime == null) return;
        var pos = ctx.input.length();
        ctx.matches.add(new Match("mimetype", mime, pos, pos, "", 1000, Set.of(), false));
    }
}
```

- [ ] **Step 4: Wire into `Rules.defaultPipeline()` `PostPhase` after `TypeProcessor`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=MimetypeProcessorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/post/MimetypeProcessor.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/MimetypeProcessorTest.java
git commit -m "feat(rules): add MimetypeProcessor with JDK + small overlay"
```

---

## Task 11: `EnlargeGroupMatches` processor

**Files:**
- Create: `src/main/java/io/guessit/rules/post/EnlargeGroupMatches.java`
- Test:   `src/test/java/io/guessit/rules/post/EnlargeGroupMatchesTest.java`

Python: any match starting at `group.start+1` is shifted to start at `group.start`; any match ending at `group.end-1` is shifted to end at `group.end`. Runs PRE_PROCESS so subsequent rules see the enlarged match (e.g. release_group `[XCT]` brackets included).

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class EnlargeGroupMatchesTest {
    @Test void enlargeBracketedMatchToIncludeBrackets() {
        var ctx = new ParseContext("[XCT]Show", null, null);
        ctx.markers.add(new Marker("group", 0, 5)); // includes [ and ]
        ctx.matches.add(new Match("release_group", "XCT", 1, 4, "XCT", 1000, Set.of(), false));
        new EnlargeGroupMatches().apply(ctx);
        var m = ctx.matches.named("release_group").findFirst().orElseThrow();
        assertThat(m.start()).isEqualTo(0);
        assertThat(m.end()).isEqualTo(5);
    }
}
```

(Constructor signature for `ParseContext` may differ — adapt to current.)

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=EnlargeGroupMatchesTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;

public final class EnlargeGroupMatches implements PostProcessor {
    @Override
    public void apply(ParseContext ctx) {
        for (var g : ctx.markers) {
            if (!g.name().equals("group")) continue;
            var snapshot = new ArrayList<>(ctx.matches.snapshot());
            for (var m : snapshot) {
                Match next = null;
                if (m.start() == g.start() + 1) {
                    next = m.withStart(g.start());
                } else if (m.end() == g.end() - 1) {
                    next = m.withEnd(g.end());
                }
                if (next != null) ctx.matches.replace(m, next);
            }
        }
    }
}
```

(If `Match.withStart`/`withEnd` helpers don't exist, add them as record-with copies — `Match` is already a record; pull them up while you're here. Tiny scope.)

- [ ] **Step 4: Wire into `defaultPipeline()` PostPhase as the **first** processor**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=EnlargeGroupMatchesTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/post/EnlargeGroupMatches.java src/main/java/io/guessit/engine/Match.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/EnlargeGroupMatchesTest.java
git commit -m "feat(rules): add EnlargeGroupMatches PRE_PROCESS rule"
```

---

## Task 12: `RemoveLessSpecificSeasonEpisode` + `RemoveAmbiguous`

**Files:**
- Create: `src/main/java/io/guessit/rules/post/RemoveAmbiguous.java`
- Create: `src/main/java/io/guessit/rules/post/RemoveLessSpecificSeasonEpisode.java`
- Test:   `src/test/java/io/guessit/rules/post/RemoveAmbiguousTest.java`
- Test:   `src/test/java/io/guessit/rules/post/RemoveLessSpecificSeasonEpisodeTest.java`

Python `processors.py`:
- `RemoveAmbiguous` walks fileparts in order; for each property, the first filepart's values "win"; later fileparts only keep matches whose value already exists in `values[name]`.
- `RemoveLessSpecificSeasonEpisode(name)` — same but fileparts walked in **reverse** order, with priority given to matches tagged `SxxExx`.

- [ ] **Step 1: Failing test for `RemoveAmbiguous`**

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveAmbiguousTest {
    @Test void firstFilepartReleaseGroupWinsOverSecond() {
        // Top dir has release_group=Group; file inside repeats with different value.
        var r = Guessit.parse("Show.S01.Group/Show.S01E01.OtherGroup.mkv");
        // Without dedup, we'd get both. After RemoveAmbiguous, the upper-folder value survives.
        assertThat(r.releaseGroup()).isIn("Group", "OtherGroup");
    }
}
```

(This test is mostly a smoke check — the precise behaviour will be exercised by the YML suite at Task 21. A more rigorous pure-engine unit test is acceptable; build it directly against `ctx.matches` if YML proves indirect.)

- [ ] **Step 2: Run → may pass or fail depending on prior state; the gate is YML at Task 21.**

- [ ] **Step 3: Implement `RemoveAmbiguous`**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.*;
import java.util.function.*;

public final class RemoveAmbiguous implements PostProcessor {
    private final Predicate<Match> predicate;
    private final boolean reverseFileparts;
    private final Comparator<Match> tieBreak;

    public RemoveAmbiguous() { this(m -> true, false, (a,b)->0); }
    public RemoveAmbiguous(Predicate<Match> predicate,
                           boolean reverseFileparts,
                           Comparator<Match> tieBreak) {
        this.predicate = predicate;
        this.reverseFileparts = reverseFileparts;
        this.tieBreak = tieBreak;
    }

    @Override
    public void apply(ParseContext ctx) {
        var paths = ctx.markers.stream()
            .filter(m -> m.name().equals("path"))
            .sorted(Comparator.comparingInt(Marker::start))
            .toList();
        if (reverseFileparts) {
            var copy = new ArrayList<>(paths);
            Collections.reverse(copy);
            paths = copy;
        }
        var seenNames = new HashSet<String>();
        var values = new HashMap<String, List<Object>>();
        var toRemove = new ArrayList<Match>();
        for (var fp : paths) {
            var inFp = ctx.matches.snapshot().stream()
                .filter(predicate)
                .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
                .sorted(tieBreak)
                .toList();
            var fpNames = new HashSet<String>();
            for (var m : inFp) {
                fpNames.add(m.name());
                var bucket = values.computeIfAbsent(m.name(), k -> new ArrayList<>());
                if (seenNames.contains(m.name())) {
                    if (!bucket.contains(m.value())) toRemove.add(m);
                } else {
                    if (!bucket.contains(m.value())) bucket.add(m.value());
                }
            }
            seenNames.addAll(fpNames);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Implement `RemoveLessSpecificSeasonEpisode`**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.Comparator;

public final class RemoveLessSpecificSeasonEpisode extends RemoveAmbiguous {
    public RemoveLessSpecificSeasonEpisode(String name) {
        super(m -> m.name().equals(name),
              true,                                       // reverse fileparts
              Comparator.comparing((Match m) -> m.tags().contains("SxxExx") ? 0 : 1));
    }
}
```

- [ ] **Step 5: Wire into `defaultPipeline()` PostPhase, in this order:**
  `RemoveLessSpecificSeasonEpisode("season")`, `RemoveLessSpecificSeasonEpisode("episode")`, `RemoveAmbiguous`. Place between `EquivalentHoles` and `TypeProcessor`.

- [ ] **Step 6: Run smoke test**

Run: `mvn -q test -Dtest=RemoveAmbiguousTest`
Expected: PASS or YML-gated.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/guessit/rules/post/RemoveAmbiguous.java src/main/java/io/guessit/rules/post/RemoveLessSpecificSeasonEpisode.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/RemoveAmbiguousTest.java
git commit -m "feat(rules): port RemoveAmbiguous + RemoveLessSpecificSeasonEpisode"
```

---

## Task 13: `SeasonYear` + `YearSeason` processors

**Files:**
- Create: `src/main/java/io/guessit/rules/post/SeasonYear.java`
- Create: `src/main/java/io/guessit/rules/post/YearSeason.java`
- Test:   `src/test/java/io/guessit/rules/post/SeasonYearTest.java`
- Test:   `src/test/java/io/guessit/rules/post/YearSeasonTest.java`

Python:
- `SeasonYear` — when no `year` exists and there is a `season` whose value passes `valid_year` (1900..currentYear+1), emit a `year` match cloned from that season.
- `YearSeason` — when no `season` exists but there is a `year` and an `episode`, emit a `season` match cloned from the year.

- [ ] **Step 1: Failing tests**

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonYearTest {
    @Test void seasonValueLooksLikeYearAddsYear() {
        var r = Guessit.parse("Show.S2014.E03.mkv"); // S2014 → season=2014 + year=2014
        assertThat(r.year()).isEqualTo(2014);
    }
}
```

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YearSeasonTest {
    @Test void yearWithEpisodeNoSeasonAddsSeason() {
        var r = Guessit.parse("Show.2014.E03.mkv"); // year=2014 + episode=3 → season=2014
        assertThat(r.season()).isEqualTo(2014);
    }
}
```

- [ ] **Step 2: Run → likely fail**

Run: `mvn -q test -Dtest=SeasonYearTest,YearSeasonTest`
Expected: FAIL.

- [ ] **Step 3: Implement `SeasonYear`**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.Set;

public final class SeasonYear implements PostProcessor {
    private static final int CUR = java.time.Year.now().getValue();

    @Override
    public void apply(ParseContext ctx) {
        if (ctx.matches.named("year").findFirst().isPresent()) return;
        ctx.matches.named("season").toList().forEach(season -> {
            if (!(season.value() instanceof Integer v)) return;
            if (v < 1900 || v > CUR + 1) return;
            ctx.matches.add(new Match("year", v, season.start(), season.end(),
                season.raw(), season.priority(), Set.copyOf(season.tags()), false));
        });
    }
}
```

- [ ] **Step 4: Implement `YearSeason`**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.Set;

public final class YearSeason implements PostProcessor {
    @Override
    public void apply(ParseContext ctx) {
        if (ctx.matches.named("season").findFirst().isPresent()) return;
        if (ctx.matches.named("episode").findFirst().isEmpty()) return;
        ctx.matches.named("year").toList().forEach(year ->
            ctx.matches.add(new Match("season", year.value(), year.start(), year.end(),
                year.raw(), year.priority(), Set.copyOf(year.tags()), false)));
    }
}
```

- [ ] **Step 5: Wire after `SeasonYearLink` (which already exists), before `RemoveLessSpecificSeasonEpisode`**

- [ ] **Step 6: Run → green**

Run: `mvn -q test -Dtest=SeasonYearTest,YearSeasonTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/guessit/rules/post/SeasonYear.java src/main/java/io/guessit/rules/post/YearSeason.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/SeasonYearTest.java src/test/java/io/guessit/rules/post/YearSeasonTest.java
git commit -m "feat(rules): port SeasonYear + YearSeason processors"
```

---

## Task 14: `StripSeparators` processor

**Files:**
- Create: `src/main/java/io/guessit/rules/post/StripSeparators.java`
- Test:   `src/test/java/io/guessit/rules/post/StripSeparatorsTest.java`

Python: trims leading/trailing separator chars from each match's `raw`, **but** preserves single-char acronym dots (e.g. `S.H.I.E.L.D.` keeps inner+outer dots when each side is a single char).

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripSeparatorsTest {
    @Test void trimsLeadingTrailingSeps() {
        var ctx = new ParseContext(".Show Name.", null, null);
        ctx.matches.add(new Match("title", "Show Name", 0, 11, ".Show Name.", 1000, java.util.Set.of(), false));
        new StripSeparators().apply(ctx);
        var m = ctx.matches.named("title").findFirst().orElseThrow();
        assertThat(m.start()).isEqualTo(1);
        assertThat(m.end()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Run → fails**

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.ArrayList;

public final class StripSeparators implements PostProcessor {
    @Override
    public void apply(ParseContext ctx) {
        for (var m : new ArrayList<>(ctx.matches.snapshot())) {
            int s = m.start(), e = m.end();
            while (s < e && Seps.isSep(ctx.input.charAt(s))
                && (e - s < 3 || !Seps.isSep(ctx.input.charAt(s + 2)))) s++;
            while (e > s && Seps.isSep(ctx.input.charAt(e - 1))
                && (e - s < 3 || !Seps.isSep(ctx.input.charAt(e - 3)))) e--;
            if (s != m.start() || e != m.end()) {
                ctx.matches.replace(m, m.withStart(s).withEnd(e));
            }
        }
    }
}
```

- [ ] **Step 4: Wire as the **last** PostPhase processor before `PrivateRemover`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=StripSeparatorsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/post/StripSeparators.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/StripSeparatorsTest.java
git commit -m "feat(rules): port StripSeparators (acronym-aware)"
```

---

## Task 15: `EpisodeNumberSeparatorRange` (anime ranges)

**Files:**
- Create: `src/main/java/io/guessit/rules/post/EpisodeNumberSeparatorRange.java`
- Test:   `src/test/java/io/guessit/rules/post/EpisodeNumberSeparatorRangeTest.java`

Python: `AbstractSeparatorRange` (`/tmp/guessit/guessit/rules/properties/episodes.py`) — when two episode matches share the same path marker and are separated by a single `-`, `~`, or word `to`, expand the range into a `List<Integer>` (jump ≤ 100 sanity bound).

Failure cluster: `Bleach 313-314`, `Hatsuyuki 16-20 (191-195)`.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodeNumberSeparatorRangeTest {
    @Test void expandsHyphenRange() {
        var r = Guessit.parse("[Hatsuyuki]_Bleach_-_16-20_(191-195)_[1280x720].mkv");
        assertThat(r.episodeList()).contains(16, 17, 18, 19, 20);
    }
}
```

- [ ] **Step 2: Run → fails**

- [ ] **Step 3: Implement**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;

import java.util.*;

public final class EpisodeNumberSeparatorRange implements PostProcessor {
    @Override
    public void apply(ParseContext ctx) {
        var eps = ctx.matches.named("episode")
            .sorted(Comparator.comparingInt(Match::start))
            .toList();
        for (int i = 0; i + 1 < eps.size(); i++) {
            var a = eps.get(i);
            var b = eps.get(i + 1);
            if (!(a.value() instanceof Integer va && b.value() instanceof Integer vb)) continue;
            if (vb <= va || vb - va > 100) continue;
            if (a.end() > b.start()) continue;
            var gap = ctx.input.substring(a.end(), b.start());
            if (!gap.matches("(?i)[-_~]|\\s*to\\s*")) continue;
            for (int v = va + 1; v < vb; v++) {
                int pos = a.end();
                ctx.matches.add(new Match("episode", v, pos, pos, "",
                    a.priority(), Set.of("range-filled"), false));
            }
        }
    }
}
```

- [ ] **Step 4: Wire after `RangeFiller`**

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=EpisodeNumberSeparatorRangeTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/post/EpisodeNumberSeparatorRange.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/EpisodeNumberSeparatorRangeTest.java
git commit -m "feat(rules): expand episode N-M ranges (anime)"
```

---

## Task 16: `LanguageCountryAttach` (pt-BR / de-CH)

**Files:**
- Create: `src/main/java/io/guessit/rules/post/LanguageCountryAttach.java`
- Test:   `src/test/java/io/guessit/rules/post/LanguageCountryAttachTest.java`
- Modify: `src/main/java/io/guessit/lang/Language.java` if it lacks a `country` field

Python: `language.py` parses tokens like `pt-BR`, `de-CH`, `en-US` into a `Language` whose `country` is set; `LanguageRegistry.find("pt-BR")` returns a `Language` with `country.alpha2 == "BR"`. The Java `Language` record already has `alpha2/alpha3/name`; **add** an optional `Country country` component (nullable).

Failure cluster (4–6 cases): `pt-BR`, `de-CH`, `en-US` etc. attach to language only, country list stays empty when language already carries one.

- [ ] **Step 1: Inspect & extend `Language` record**

If `Language` is currently `record Language(String alpha2, String alpha3, String name)`, add a fourth optional field:

```java
public record Language(String alpha2, String alpha3, String name, io.guessit.lang.Country country) {
    public Language(String alpha2, String alpha3, String name) {
        this(alpha2, alpha3, name, null);
    }
}
```

(The compact constructor preserves existing call sites.)

- [ ] **Step 2: Failing test**

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageCountryAttachTest {
    @Test void ptBrBecomesPortugueseWithBrazilCountry() {
        var r = Guessit.parse("Movie.pt-BR.1080p.mkv");
        assertThat(r.language()).hasSize(1);
        var l = r.language().getFirst();
        assertThat(l.alpha3()).isEqualTo("por");
        assertThat(l.country()).isNotNull();
        assertThat(l.country().alpha2()).isEqualTo("BR");
        assertThat(r.country()).isNullOrEmpty();
    }
    @Test void deChBecomesGermanWithSwissCountry() {
        var r = Guessit.parse("Movie.de-CH.1080p.mkv");
        var l = r.language().getFirst();
        assertThat(l.alpha2()).isEqualTo("de");
        assertThat(l.country().alpha2()).isEqualTo("CH");
    }
}
```

- [ ] **Step 3: Run → fails**

Run: `mvn -q test -Dtest=LanguageCountryAttachTest`
Expected: FAIL.

- [ ] **Step 4: Implement**

```java
package io.guessit.rules.post;

import io.guessit.engine.*;
import io.guessit.lang.*;

import java.util.*;

public final class LanguageCountryAttach implements PostProcessor {
    @Override
    public void apply(ParseContext ctx) {
        var lang = ctx.matches.named("language")
            .sorted(Comparator.comparingInt(Match::start))
            .toList();
        for (var l : lang) {
            // Look at the chars right after l.end() — must be sep + 2-letter country.
            int s = l.end();
            if (s + 3 > ctx.input.length()) continue;
            if (!Seps.isSep(ctx.input.charAt(s))) continue;
            var token = ctx.input.substring(s + 1, Math.min(s + 3, ctx.input.length()));
            var country = LanguageRegistry.instance().findCountry(token).orElse(null);
            if (country == null) continue;
            // Drop any country match overlapping the [s+1, s+3) range we just consumed.
            var countryMatches = ctx.matches.named("country")
                .filter(m -> m.start() == s + 1 && m.end() == s + 3)
                .toList();
            countryMatches.forEach(ctx.matches::remove);
            // Replace language with attached-country variant.
            var oldLang = (Language) l.value();
            var newLang = new Language(oldLang.alpha2(), oldLang.alpha3(), oldLang.name(), country);
            ctx.matches.replace(l, l.withValue(newLang).withEnd(s + 3));
        }
    }
}
```

- [ ] **Step 5: Wire after `LanguageExtractor` (PostPhase, before `RemoveAmbiguous`)**

- [ ] **Step 6: Run → green**

Run: `mvn -q test -Dtest=LanguageCountryAttachTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/guessit/lang/Language.java src/main/java/io/guessit/rules/post/LanguageCountryAttach.java src/main/java/io/guessit/rules/Rules.java src/test/java/io/guessit/rules/post/LanguageCountryAttachTest.java
git commit -m "feat(rules): attach country to language for pt-BR/de-CH/en-US patterns"
```

---

## Task 17: AltTitle multi-split on " - "

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/EpisodeTitleExtractor.java` (the `AlternativeTitleReplace` sub-rule)

Failure cluster (3 cases): `Echec et Mort - Hard to Kill - Steven Seagal Multi`, `Lola At Your Service - Marc Dorcel`, `Anna - Beautiful Ass`. Python's `AlternativeTitleReplace` splits the *title hole* on the first ` - ` and assigns the suffix to `alternative_title`. The Java port currently only handles the single-split case; multi `" - "` needs to keep first as title, second as alternative_title, drop later parts.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodeTitleExtractorAltSplitTest {
    @Test void splitsTitleAndAlternativeOnDashSpaceDash() {
        var r = Guessit.parse("Echec et Mort - Hard to Kill - Steven Seagal Multi.mkv");
        assertThat(r.title()).isEqualTo("Echec et Mort");
        assertThat(r.alternativeTitle()).isEqualTo("Hard to Kill");
    }
    @Test void singleDashSplitsBoth() {
        var r = Guessit.parse("Lola At Your Service - Marc Dorcel.mkv");
        assertThat(r.title()).isEqualTo("Lola At Your Service");
        assertThat(r.alternativeTitle()).isEqualTo("Marc Dorcel");
    }
}
```

- [ ] **Step 2: Run → fails**

- [ ] **Step 3: Implement**

In `EpisodeTitleExtractor.AlternativeTitleReplace` (or `TitleExtractor` if alt logic lives there — the plan-4 doc says alt comes out of `EpisodeTitleExtractor`), change the split to be:

```java
// Pseudocode locator: find the existing place that splits a hole text on " - ".
var parts = holeText.split("\\s+-\\s+", -1);
if (parts.length >= 2) {
    titleText = parts[0].trim();
    altTitleText = parts[1].trim();
    // Anything after parts[1] is dropped — extra parts are noise (artist/quality tokens).
}
```

Replace the current single-split path with the above. Strip surrounding seps via `Formatters.strip` (already exists).

- [ ] **Step 4: Run → green**

Run: `mvn -q test -Dtest=EpisodeTitleExtractorAltSplitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/EpisodeTitleExtractor.java src/test/java/io/guessit/rules/property/EpisodeTitleExtractorAltSplitTest.java
git commit -m "fix(rules): split title/alt on first \" - \", drop later parts"
```

---

## Task 18: Compact-SSEE last-vs-first (`the.100.109` keeps season=1 episode=9)

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/SeasonEpisodeExtractor.java`

Failure cluster (4 cases): `[401] Fun Run` → episode=1 (currently 401), `the.100.109` → season=1 episode=9, `11.22.63.106` → episode=6, `foobar.213` → episode=13. Python `episodes.py` `WeakDuplicate` keeps the **last** weak SSEE pair when multiple compact 3-digit candidates exist in the same filepart — Java currently keeps the first.

- [ ] **Step 1: Failing tests** (one assertion per cluster case)

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonEpisodeCompactLastTest {
    @Test void the100_109_keepsSeason1Episode9() {
        var r = Guessit.parse("the.100.109.mkv");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(9);
    }
    @Test void bracket401_funRun_keepsEpisode1() {
        var r = Guessit.parse("[401] Fun Run.mkv");
        assertThat(r.episode()).isEqualTo(1);
        assertThat(r.season()).isEqualTo(4);
    }
    @Test void e112263106_keepsEpisode6() {
        var r = Guessit.parse("11.22.63.106.mkv");
        assertThat(r.episode()).isEqualTo(6);
    }
    @Test void foobar_213_keepsEpisode13() {
        var r = Guessit.parse("foobar.213.mkv");
        assertThat(r.episode()).isEqualTo(13);
        assertThat(r.season()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run → fails (some or all)**

- [ ] **Step 3: Locate the dedup pass and reverse-iterate per pattern**

In `SeasonEpisodeExtractor.removeWeakDuplicate(...)` (or the closest analogue named per memory file `plan_4_status.md`), the existing logic iterates left-to-right keeping the **first** match. Change to iterate right-to-left over each pattern's hits within a filepart so the **last** weak-duplicate season+episode pair survives:

```java
// existing:  for (var hit : hitsLeftToRight) keepIfFirst(...);
// new:
var grouped = hits.stream().collect(Collectors.groupingBy(MatchedPattern::patternKey));
for (var entry : grouped.entrySet()) {
    var list = new ArrayList<>(entry.getValue());
    Collections.reverse(list);              // last-wins
    for (var h : list) keepIfFirst(h, ...); // existing logic
}
```

(Use the actual symbol names from `SeasonEpisodeExtractor.java`. Memory notes the function is currently structured around `removeInvalidSecondaryChain` + WeakDuplicateExtractor — find the relevant call site by grep for `weak-duplicate` in `SeasonEpisodeExtractor.java` / `WeakDuplicateExtractor.java`.)

- [ ] **Step 4: Run → green**

Run: `mvn -q test -Dtest=SeasonEpisodeCompactLastTest`
Expected: PASS for all four assertions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/SeasonEpisodeExtractor.java src/main/java/io/guessit/rules/property/WeakDuplicateExtractor.java src/test/java/io/guessit/rules/property/SeasonEpisodeCompactLastTest.java
git commit -m "fix(rules): keep LAST weak-duplicate compact-SSEE per filepart (mirror python)"
```

---

## Task 19: `expected_title: re:` regex syntax + per-case overrides

**Files:**
- Modify: `src/main/java/io/guessit/Options.java`
- Create: `src/main/java/io/guessit/rules/property/ExpectedTitleRegex.java`
- Modify: `src/main/java/io/guessit/rules/property/TitleExtractor.java`
- Test:   `src/test/java/io/guessit/rules/property/ExpectedTitleRegexTest.java`

Failure cluster (8 cases tagged `[]` in memory file). Python's expected-title list supports an `re:` prefix that compiles the rest as a regex. The Java `Options.expectedTitle` currently only honours plain strings.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExpectedTitleRegexTest {
    @Test void reColonAcceptsAnyMatchingTitle() {
        var opts = Options.builder()
            .expectedTitle(List.of("re:my \\d+p show"))
            .build();
        var r = Guessit.parse("my 720p show S01E02.mkv", opts);
        assertThat(r.title()).isEqualTo("my 720p show");
    }
}
```

- [ ] **Step 2: Run → fails**

- [ ] **Step 3: Implement helper**

```java
package io.guessit.rules.property;

import java.util.*;
import java.util.regex.Pattern;

public final class ExpectedTitleRegex {
    public record Entry(Pattern pattern, String literalReplacement) {}

    public static List<Entry> parse(List<String> raw) {
        if (raw == null) return List.of();
        var out = new ArrayList<Entry>(raw.size());
        for (var s : raw) {
            if (s.startsWith("re:")) {
                out.add(new Entry(Pattern.compile(s.substring(3), Pattern.CASE_INSENSITIVE), null));
            } else {
                // literal — escape and case-fold for Pattern.compile.
                out.add(new Entry(Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE), s));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Use it from `TitleExtractor.extract`**

Find the loop in `TitleExtractor.extract` that walks `ctx.options.expectedTitle()`. Replace with a loop over `ExpectedTitleRegex.parse(...)` and use `entry.pattern().matcher(ctx.input).find()`. When the entry is a literal (`literalReplacement != null`) and the user-supplied casing differs from input, emit the user-supplied form as the title value (mirrors python `expected_title.find_value`).

- [ ] **Step 5: Run → green**

Run: `mvn -q test -Dtest=ExpectedTitleRegexTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/ExpectedTitleRegex.java src/main/java/io/guessit/rules/property/TitleExtractor.java src/test/java/io/guessit/rules/property/ExpectedTitleRegexTest.java
git commit -m "feat(options): expected_title 're:' regex syntax"
```

---

## Task 20: Outer-folder release-group hand-off (`0106` case)

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/ReleaseGroupExtractor.java`

Failure cluster (1 case): expected `release_group=Group` from upper folder name; current code emits `group` (lowercase) from file name. Python `release_group.py` `ScenicReleaseGroup` walks fileparts back-to-front and prefers the upper folder when both have a candidate. Memory file says detection runs in `postProcess` — not extract.

- [ ] **Step 1: Failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseGroupOuterFolderTest {
    @Test void prefersUpperFolderCasing() {
        // Upper folder "Show.S01.Group", file "show.s01e06.group.mkv" → Group wins.
        var r = Guessit.parse("Show.S01.Group/show.s01e06.group.mkv");
        assertThat(r.releaseGroup()).isEqualTo("Group");
    }
}
```

- [ ] **Step 2: Run → fails**

- [ ] **Step 3: Implement**

Inside `ReleaseGroupExtractor.postProcess`, after the per-filepart RG candidates are collected, when more than one filepart contributed a candidate with the same case-insensitive value, pick the **upper**-most filepart's casing. Implementation sketch (insert before the final `add` to `ctx.matches`):

```java
// Group RG candidates by case-insensitive value, keep the one in the lowest-start filepart.
var byKey = candidates.stream().collect(Collectors.groupingBy(
    c -> c.value().toString().toLowerCase(java.util.Locale.ROOT)));
candidates.clear();
for (var bucket : byKey.values()) {
    bucket.sort(Comparator.comparingInt(Match::start));
    candidates.add(bucket.getFirst());
}
```

(Adapt to actual `candidates` variable name in the file.)

- [ ] **Step 4: Run → green**

Run: `mvn -q test -Dtest=ReleaseGroupOuterFolderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/ReleaseGroupExtractor.java src/test/java/io/guessit/rules/property/ReleaseGroupOuterFolderTest.java
git commit -m "fix(rules): release_group prefers upper-folder casing on duplicate"
```

---

## Task 20a: Activate `AbsoluteEpisodeRule.postProcess` (port `RenameToAbsoluteEpisode`)

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/AbsoluteEpisodeRule.java`
- Test:   `src/test/java/io/guessit/rules/property/AbsoluteEpisodeRuleTest.java`

Failure cluster (3 cases needing `absolute_episode`). Python source: `/tmp/guessit/guessit/rules/properties/episodes.py:573` (`RenameToAbsoluteEpisode`). Current Java stub is intentionally a no-op (`extract` and `postProcess` both inert) — the prior leading-rename was too aggressive and broke `12.Monkeys`/`24`/`4400`. Rewrite `postProcess` to mirror python exactly: must run **after** Task 15's `EpisodeNumberSeparatorRange` so both range groups exist as `episode` matches.

Python algorithm:
1. Group all `episode` matches by their initiator (the parent regex hit). Keep initiators with ≥2 episode children.
2. **Two-initiator branch:** if exactly two such initiators exist in the filepart, separator-only gap between them, equal child count → the higher-end-position initiator's children become `absolute_episode`.
3. **Single-initiator (or zero) branch:** for each filepart, if a SxxExx-tagged episode exists, weak-episode children that *start at the filepart start* become `absolute_episode`. Otherwise (no SxxExx), they are removed.

- [ ] **Step 1: Failing tests**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbsoluteEpisodeRuleTest {
    @Test void bleachTwoGroupsHigherBecomesAbsolute() {
        var r = Guessit.parse("Bleach - s16e03-04 - 313-314");
        assertThat(r.episodeList()).containsExactly(3, 4);
        assertThat(r.field("absolute_episode")).isEqualTo(java.util.List.of(313, 314));
    }
    @Test void hatsuyukiAnimeRangeWithParensAbsolute() {
        var r = Guessit.parse("[Hatsuyuki-Kaitou]_Fairy_Tail_2_-_16-20_(191-195)_[720p][10bit].torrent");
        assertThat(r.episodeList()).containsExactly(16, 17, 18, 19, 20);
        assertThat(r.field("absolute_episode")).isEqualTo(java.util.List.of(191, 192, 193, 194, 195));
    }
    @Test void absoluteEpisodeNotEmittedForShortMovieTitleNumber() {
        // regression guard: 12.Monkeys / 24 / 4400 must NOT become absolute_episode
        assertThat(Guessit.parse("12.Monkeys.1995.mkv").field("absolute_episode")).isNull();
        assertThat(Guessit.parse("24.S01E01.mkv").field("absolute_episode")).isNull();
    }
}
```

- [ ] **Step 2: Run → fails**

Run: `mvn -q test -Dtest=AbsoluteEpisodeRuleTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

`Match` does not currently carry an `initiator` reference (Python rebulk concept). Approximate with two readable signals already on Java matches:
- `tags.contains("range-filled")` → emitted by `EpisodeNumberSeparatorRange` (Task 15).
- `tags.contains("weak-episode")` → emitted by `WeakEpisodeExtractor`.
- Origin span = `(start, end)` of the originating regex hit; group siblings sit within that span. For Java, treat **contiguous episode matches sharing the same outer parent marker (group)** as one group. Concretely: split episodes per filepart into groups by the **enclosing group marker**, falling back to "all consecutive episode matches separated only by `range-filled` ones" as one group.

```java
@Override
public void postProcess(ParseContext ctx) {
    var pathMarkers = ctx.markers.stream()
        .filter(m -> m.name().equals("path"))
        .toList();
    for (var fp : pathMarkers) {
        var eps = ctx.matches.snapshot().stream()
            .filter(m -> m.name().equals("episode"))
            .filter(m -> m.start() >= fp.start() && m.end() <= fp.end())
            .sorted(java.util.Comparator.comparingInt(io.guessit.engine.Match::start))
            .toList();
        if (eps.isEmpty()) continue;

        // Group eps by enclosing group marker (or "no-group" bucket keyed by start position run).
        var groups = new java.util.LinkedHashMap<Object, java.util.List<io.guessit.engine.Match>>();
        for (var e : eps) {
            var enc = ctx.markers.stream()
                .filter(g -> g.name().equals("group")
                          && g.start() <= e.start() && g.end() >= e.end())
                .findFirst().orElse(null);
            var key = enc != null ? enc : "noenc-" + e.start();
            // Coalesce range-filled successors into the same key as the previous match.
            if (e.tags().contains("range-filled")) {
                var lastKey = groups.keySet().stream().reduce((a,b)->b).orElse(key);
                key = lastKey;
            }
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
        }
        var multi = groups.values().stream().filter(g -> g.size() >= 2).toList();

        if (multi.size() == 2) {
            var sorted = multi.stream()
                .sorted(java.util.Comparator.comparingInt(g -> g.getLast().end()))
                .toList();
            var lower = sorted.get(0);
            var higher = sorted.get(1);
            // Separator-only gap between the two groups.
            int gapStart = lower.getLast().end();
            int gapEnd = higher.getFirst().start();
            boolean sepOnly = ctx.input.substring(gapStart, gapEnd).chars()
                .allMatch(c -> io.guessit.engine.Seps.isSep((char) c));
            if (sepOnly && lower.size() == higher.size()) {
                for (var m : higher) {
                    ctx.matches.replace(m, m.withName("absolute_episode"));
                }
                continue;
            }
        }

        // Single-or-zero-multi-group fallback.
        boolean hasSxxExx = ctx.matches.snapshot().stream().anyMatch(m ->
            m.name().equals("episode") && m.tags().contains("SxxExx")
            && m.start() >= fp.start() && m.end() <= fp.end());
        var leadingWeak = eps.stream()
            .filter(e -> e.tags().contains("weak-episode") && e.start() == fp.start())
            .toList();
        if (leadingWeak.isEmpty()) continue;
        if (hasSxxExx) {
            for (var m : leadingWeak) ctx.matches.replace(m, m.withName("absolute_episode"));
        } else {
            for (var m : leadingWeak) ctx.matches.remove(m);
        }
    }
}
```

(`Match.withName(String)` joins the small set of with-helpers added in Task 11. Add it alongside `withStart`/`withEnd`/`withValue`.)

- [ ] **Step 4: Run → green**

Run: `mvn -q test -Dtest=AbsoluteEpisodeRuleTest`
Expected: PASS for all three.

- [ ] **Step 5: Sanity-check Task 15 still green (range expand alone, no two-group case)**

Run: `mvn -q test -Dtest=EpisodeNumberSeparatorRangeTest`
Expected: PASS — `Bleach 16-20 (191-195)` now produces both episode list AND absolute_episode list; the assertion in Task 15 only checks `episodeList contains 16..20` so it still holds.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/AbsoluteEpisodeRule.java src/main/java/io/guessit/engine/Match.java src/test/java/io/guessit/rules/property/AbsoluteEpisodeRuleTest.java
git commit -m "feat(rules): activate AbsoluteEpisodeRule (port RenameToAbsoluteEpisode)"
```

---

## Task 21: Widen YML parity gate to ALL keys + 100% threshold

**Files:**
- Modify: `src/test/java/io/guessit/parity/YmlParityTest.java`

- [ ] **Step 1: Drop `PHASE_PROPS` filter**

In `allYmlCases()` (line ~145), the current code filters cases via `PHASE_PROPS.containsAll(c.expected().keySet())`. Remove that filter so **every** YML case runs.

```java
// before:
return YmlTestLoader.discoverAll("yml/")
    .filter(c -> PHASE_PROPS.containsAll(c.expected().keySet()));

// after:
return YmlTestLoader.discoverAll("yml/");
```

- [ ] **Step 2: Add any missing key↔field mappings to `KEY_TO_FIELDS`**

Verify `KEY_TO_FIELDS` covers every property in `GuessResult` plus `crc32`, `bonus`, `bonus_title`, `film`, `film_title`, `cd`, `cd_count`, `part`, `version`, `absolute_episode`, `disc`, `week`, `episode_details`. (Most already present per existing scan.) Add any missing entries. `absolute_episode`, `disc`, `week`, `episode_details` route through `GuessResult.field(...)` (extras bucket), not direct fields.

- [ ] **Step 3: Run parity suite**

Run: `mvn -q test -Dtest=YmlParityTest`
Expected: 1678/1678 pass.

If anything below 100%, **stop and triage**: capture the failing cases via the `IDEA execute_run_configuration` MCP path documented in memory (`plan_4_status.md` ▸ "How to apply"), categorise into the still-listed clusters in `yml_parity_clusters.md`, and add a follow-up surgical fix as Task 21a/b/c. Do not lower the threshold; do not re-introduce a `PHASE_PROPS` filter.

- [ ] **Step 4: Run full suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/guessit/parity/YmlParityTest.java
git commit -m "test: drop PHASE_PROPS filter — all YML cases must pass (100%)"
```

---

## Task 22: End-to-end verification + memory update

- [ ] **Step 1: Build**

Run: `mvn -q -DskipTests package`
Expected: builds `target/guessit-java-*.jar` and `target/guessit-java-*-cli.jar`.

- [ ] **Step 2: Full test suite**

Run: `mvn -q test`
Expected: PASS, parity at 1678/1678.

- [ ] **Step 3: CLI smoke — episode**

```
java -jar target/guessit-java-*-cli.jar "Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv"
```
Expected: `title=Show Name`, `season=1`, `episode=2`, `episode_title=Episode Title`, `screen_size=720p`, `source=HDTV`, `video_codec=H.264`, `release_group=RG`, `container=mkv`, `mimetype=video/x-matroska`, `type=episode`.

- [ ] **Step 4: CLI smoke — movie + new fields**

```
java -jar target/guessit-java-*-cli.jar "The.Matrix.1999.1080p.BluRay.4.7gb.Extended-WiKi.mkv"
```
Expected: `title=The Matrix`, `year=1999`, `screen_size=1080p`, `source=Blu-ray`, `size=4.7 GB`, `edition=Extended`, `release_group=WiKi`, `container=mkv`, `mimetype=video/x-matroska`, `type=movie`.

- [ ] **Step 5: CLI smoke — bonus + film_title**

```
java -jar target/guessit-java-*-cli.jar "Behind.The.Magic.f01.mkv"
java -jar target/guessit-java-*-cli.jar "Trilogy.Special.Features.x05.Making.Of.mkv"
```
Expected: first → `film=1`, `film_title=Behind The Magic`; second → `bonus=5`, `bonus_title=Making Of`.

- [ ] **Step 6: Update memory**

Replace `plan_4_status.md` content with a Phase-5 status entry:
- Bump status line to `100% YML parity (1678/1678) at commit <new-sha>`.
- Note Phase-5 extractors landed.
- Remove `yml_parity_clusters.md` (stale once 100%) — both `Write` to delete via the memory mechanism by emptying the file, then drop its line from `MEMORY.md`.
- Add a new memory `phase_5_complete.md` summarising the new extractors and processor wiring.

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/plans/2026-05-04-plan-5-phase5-longtail.md
git commit -m "docs: phase-5 verification — 100% YML parity"
```

---

## Verification

End-to-end checklist (must all be green at plan close):

1. `mvn -q -DskipTests package` → both jars build.
2. `mvn -q test` → entire suite green.
3. `mvn -q test -Dtest=YmlParityTest` → 1678/1678 pass.
4. CLI smoke (Tasks 22 ▸ Steps 3–5) → expected fields present in output.
5. `git status` → clean working tree, no `@Disabled` annotations remaining (`grep -rn '@Disabled' src/test/`) → empty.
6. Memory: `plan_4_status.md` updated, `yml_parity_clusters.md` removed, `phase_5_complete.md` exists, `MEMORY.md` index reflects both.

## Out of scope

- Sift DSL refactor (deferred per spec ▸ "Post-Plan-5 polish").
- `audio_bit_rate` vs `video_bit_rate` split — only restored if YML cases fail under unified `bit_rate` key (Task 3 ▸ Step 1).
- UUID functional rule (omitted from `CrcExtractor`) — re-introduce only if YML demands; Task 9 leaves the regex-only crc32 detector in place, which covers all currently-failing crc cases.
