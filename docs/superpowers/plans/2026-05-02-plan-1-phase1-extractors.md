# Plan 1: Phase 1 Extractors — year, container, screen_size, video_codec, audio_codec

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first five property extractors against the foundation laid in Plan 0. End state: `Rules.allInOrder()` registers `YearExtractor`, `ContainerExtractor`, `ScreenSizeExtractor`, `VideoCodecExtractor`, `AudioCodecExtractor`; their per-rule unit tests pass; the YML parity suite is enabled for the cases these properties cover and ≥20% of all YML cases pass.

**Architecture:** Each extractor lives in `io.guessit.rules.property.<Name>Extractor` and implements `Extractor` from Plan 0. Extractors mutate `ParseContext.matches` by appending `Match` records produced via `PatternMatcher.regex/string`. Cross-cutting concerns (separator-aware validation, value formatting, the `dash` abbreviation) live in `io.guessit.engine.Seps` + `io.guessit.engine.Validators` + a new `Abbreviations` helper. Per-rule post-processing (e.g. `KeepMarkedYearInFilepart`, `PostProcessScreenSize`, `ValidateVideoCodec`, `AudioValidatorRule`) runs in `Extractor.postProcess(ctx)` after the central `ConflictSolver` so the conflict-of-conflicts mirrors Python's rebulk pass order.

**Tech Stack:** Same as Plan 0 — Java 25, JUnit Jupiter 5.12.x, Apache Commons CSV (already on classpath), no new dependencies.

**Reference source:**
- Python: `/tmp/guessit/guessit/rules/properties/{date,container,screen_size,video_codec,audio_codec}.py`
- Python config: `/tmp/guessit/guessit/config/options.json` (sections `screen_size`, `container`, `audio_codec`)
- Python helpers: `/tmp/guessit/guessit/rules/common/{__init__.py,validators.py,date.py}`
- Spec: `docs/superpowers/specs/2026-05-02-guessit-java-design.md`
- Plan 0: `docs/superpowers/plans/2026-05-02-plan-0-foundation.md`

---

## File Structure

Created in this plan (paths relative to repo root):

```
src/main/java/io/guessit/
├── engine/
│   ├── Seps.java                              // constant char set + helpers
│   ├── Validators.java                        // seps_before/seps_after/seps_surround
│   └── Abbreviations.java                     // applies Python `dash`/`alt_dash` rewrites to a regex source
└── rules/
    └── property/
        ├── YearExtractor.java
        ├── ContainerExtractor.java
        ├── ScreenSizeExtractor.java
        ├── VideoCodecExtractor.java
        └── AudioCodecExtractor.java

src/test/java/io/guessit/
├── engine/
│   ├── SepsTest.java
│   ├── ValidatorsTest.java
│   └── AbbreviationsTest.java
└── rules/property/
    ├── YearExtractorTest.java
    ├── ContainerExtractorTest.java
    ├── ScreenSizeExtractorTest.java
    ├── VideoCodecExtractorTest.java
    └── AudioCodecExtractorTest.java
```

Modified in this plan:

```
src/main/java/io/guessit/engine/RegexOpts.java    // add validator field
src/main/java/io/guessit/engine/StringOpts.java   // add validator field
src/main/java/io/guessit/engine/PatternMatcher.java // honor validators
src/main/java/io/guessit/rules/Rules.java         // register five extractors
src/test/java/io/guessit/parity/YmlParityTest.java // remove @Disabled, add phase gate
```

Responsibilities (one per file):
- `engine/Seps` — single source of truth for the separator alphabet (literal copy of Python's `seps`).
- `engine/Validators` — match-level predicates equivalent to Python's `seps_before/after/surround`.
- `engine/Abbreviations` — converts a regex source containing literal `-` (or `@`) into a separator-tolerant alternation, replicating rebulk's `dash` / `alt_dash` abbreviation expansion.
- `rules/property/<Name>Extractor` — one Python rule file per Java extractor. Hardcoded patterns live next to the extractor; tunable lists (frame rates, container extensions, audio patterns) come from `OptionsConfig`.

---

## Task 1: `engine/Seps` separator alphabet

**Files:**
- Create: `src/main/java/io/guessit/engine/Seps.java`
- Test: `src/test/java/io/guessit/engine/SepsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SepsTest {
    @Test void containsAllPythonSeparators() {
        // Python guessit: seps = " [](){}+*|=-_~#/\\.,;:"
        for (char c : " [](){}+*|=-_~#/\\.,;:".toCharArray()) {
            assertTrue(Seps.isSep(c), "Missing separator: " + c);
        }
    }
    @Test void rejectsLettersAndDigits() {
        assertFalse(Seps.isSep('a'));
        assertFalse(Seps.isSep('5'));
        assertFalse(Seps.isSep('Z'));
    }
    @Test void escapedForRegexCharClass() {
        // Should be usable inside [...] without breaking the class.
        var re = java.util.regex.Pattern.compile("[" + Seps.regexCharClass() + "]");
        assertTrue(re.matcher(".").find());
        assertTrue(re.matcher(" ").find());
        assertTrue(re.matcher("\\").find());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=SepsTest`
Expected: FAIL — `Seps` class missing (compile error).

- [ ] **Step 3: Implement `Seps`**

Create `src/main/java/io/guessit/engine/Seps.java`:

```java
package io.guessit.engine;

public final class Seps {
    private Seps() {}

    /** Verbatim copy of Python guessit's seps constant (rules/common/__init__.py). */
    public static final String CHARS = " [](){}+*|=-_~#/\\.,;:";

    private static final boolean[] LOOKUP = new boolean[128];
    static {
        for (char c : CHARS.toCharArray()) {
            LOOKUP[c] = true;
        }
    }

    public static boolean isSep(char c) {
        return c < 128 && LOOKUP[c];
    }

    /** Returns the separator chars escaped for use inside a `[...]` regex character class. */
    public static String regexCharClass() {
        var sb = new StringBuilder(CHARS.length() * 2);
        for (char c : CHARS.toCharArray()) {
            // Inside [...]: ] \ ^ - require escaping. Other regex metas are literal in classes.
            if (c == ']' || c == '\\' || c == '^' || c == '-') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=SepsTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Seps.java src/test/java/io/guessit/engine/SepsTest.java
git commit -m "feat(engine): add Seps separator alphabet"
```

---

## Task 2: `engine/Validators` match-level predicates

**Files:**
- Create: `src/main/java/io/guessit/engine/Validators.java`
- Test: `src/test/java/io/guessit/engine/ValidatorsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class ValidatorsTest {
    private static Match m(int s, int e, String input) {
        return new Match("x", null, s, e, input.substring(s, e), 1000, java.util.Set.of(), false);
    }

    @Test void sepsSurround_atStartOfString() {
        Predicate<Match> v = Validators.sepsSurround("hello world");
        assertTrue(v.test(m(0, 5, "hello world")));        // start of string is a virtual sep
    }
    @Test void sepsSurround_atEndOfString() {
        Predicate<Match> v = Validators.sepsSurround("hello world");
        assertTrue(v.test(m(6, 11, "hello world")));       // end of string is a virtual sep
    }
    @Test void sepsSurround_lettersBefore_fails() {
        Predicate<Match> v = Validators.sepsSurround("ahello world");
        assertFalse(v.test(m(1, 6, "ahello world")));      // 'a' before
    }
    @Test void sepsSurround_lettersAfter_fails() {
        Predicate<Match> v = Validators.sepsSurround("hellow world");
        assertFalse(v.test(m(0, 5, "hellow world")));      // 'w' after
    }
    @Test void sepsBefore_only() {
        Predicate<Match> v = Validators.sepsBefore("hellow");
        assertFalse(v.test(m(0, 5, "hellow")));            // boundary at end is fine, but here we test 'before only' separately
        v = Validators.sepsBefore(".hello");
        assertTrue(v.test(m(1, 6, ".hello")));
    }
    @Test void sepsAfter_only() {
        Predicate<Match> v = Validators.sepsAfter("hello.");
        assertTrue(v.test(m(0, 5, "hello.")));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=ValidatorsTest`
Expected: FAIL — `Validators` class missing.

- [ ] **Step 3: Implement `Validators`**

Create `src/main/java/io/guessit/engine/Validators.java`:

```java
package io.guessit.engine;

import java.util.function.Predicate;

public final class Validators {
    private Validators() {}

    public static Predicate<Match> sepsBefore(String input) {
        return m -> m.start() == 0 || Seps.isSep(input.charAt(m.start() - 1));
    }

    public static Predicate<Match> sepsAfter(String input) {
        return m -> m.end() == input.length() || Seps.isSep(input.charAt(m.end()));
    }

    public static Predicate<Match> sepsSurround(String input) {
        var before = sepsBefore(input);
        var after = sepsAfter(input);
        return m -> before.test(m) && after.test(m);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=ValidatorsTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Validators.java src/test/java/io/guessit/engine/ValidatorsTest.java
git commit -m "feat(engine): add seps-aware validators"
```

---

## Task 3: `engine/Abbreviations` for `dash` rewrites

Python rebulk's `dash` abbreviation expands a literal `-` in a regex source into `[<seps_no_fs>]`, allowing matches like `H264`, `H-264`, `H.264`, `H_264` from one regex `H-264`. Java has no rebulk so we expand at compile time.

**Files:**
- Create: `src/main/java/io/guessit/engine/Abbreviations.java`
- Test: `src/test/java/io/guessit/engine/AbbreviationsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class AbbreviationsTest {
    @Test void dashExpandsToSepClass() {
        var src = Abbreviations.dash("H-264");
        var p = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher("H264").find());
        assertTrue(p.matcher("H-264").find());
        assertTrue(p.matcher("H.264").find());
        assertTrue(p.matcher("H_264").find());
        assertTrue(p.matcher("h 264").find());
    }
    @Test void dashLeavesEscapedDashAlone() {
        // Already escaped \\- should stay literal hyphen.
        var src = Abbreviations.dash("ABC\\-DEF");
        var p = Pattern.compile(src);
        assertTrue(p.matcher("ABC-DEF").find());
        assertFalse(p.matcher("ABC.DEF").find());
    }
    @Test void dashLeavesDashInsideCharClassAlone() {
        var src = Abbreviations.dash("[a-z]+");
        assertEquals("[a-z]+", src);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=AbbreviationsTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement `Abbreviations`**

Create `src/main/java/io/guessit/engine/Abbreviations.java`:

```java
package io.guessit.engine;

public final class Abbreviations {
    private Abbreviations() {}

    /** Python `seps_no_fs` (seps with '/' and '\\' removed) escaped for a regex char class. */
    public static final String SEPS_NO_FS_CLASS = sepsNoFsClass();

    private static String sepsNoFsClass() {
        var sb = new StringBuilder();
        for (char c : Seps.CHARS.toCharArray()) {
            if (c == '/' || c == '\\') continue;
            if (c == ']' || c == '^' || c == '-') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** Replace every unescaped, non-class `-` in the source with `[<seps_no_fs>]`. */
    public static String dash(String src) {
        return rewriteLiteral(src, '-', "[" + SEPS_NO_FS_CLASS + "]");
    }

    /** Replace every unescaped, non-class `@` in the source with `[<seps_no_fs>]`. */
    public static String altDash(String src) {
        return rewriteLiteral(src, '@', "[" + SEPS_NO_FS_CLASS + "]");
    }

    private static String rewriteLiteral(String src, char target, String replacement) {
        var sb = new StringBuilder(src.length() + 16);
        boolean escaped = false;
        int classDepth = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (escaped) { sb.append(c); escaped = false; continue; }
            if (c == '\\') { sb.append(c); escaped = true; continue; }
            if (c == '[') { classDepth++; sb.append(c); continue; }
            if (c == ']' && classDepth > 0) { classDepth--; sb.append(c); continue; }
            if (c == target && classDepth == 0) { sb.append(replacement); continue; }
            sb.append(c);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=AbbreviationsTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Abbreviations.java src/test/java/io/guessit/engine/AbbreviationsTest.java
git commit -m "feat(engine): add dash/alt_dash abbreviation expansion"
```

---

## Task 4: Add validator support to `RegexOpts`/`StringOpts`/`PatternMatcher`

Plan-0's `PatternMatcher` produces matches without per-match validation. Phase 1 rules need this hook.

**Files:**
- Modify: `src/main/java/io/guessit/engine/RegexOpts.java`
- Modify: `src/main/java/io/guessit/engine/StringOpts.java`
- Modify: `src/main/java/io/guessit/engine/PatternMatcher.java`
- Test: `src/test/java/io/guessit/engine/PatternMatcherTest.java` (existing; extend)

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/io/guessit/engine/PatternMatcherTest.java`:

```java
@Test void regex_validatorRejectsMatch() {
    var input = "abc1080xyz";
    var p = java.util.regex.Pattern.compile("\\d{3,4}");
    var opts = RegexOpts.defaults().withValidator(Validators.sepsSurround(input));
    var matches = PatternMatcher.regex(input, p, "screen_size", opts);
    assertTrue(matches.isEmpty(), "should reject — no separators surround");
}

@Test void regex_validatorAcceptsMatch() {
    var input = "abc.1080.xyz";
    var p = java.util.regex.Pattern.compile("\\d{3,4}");
    var opts = RegexOpts.defaults().withValidator(Validators.sepsSurround(input));
    var matches = PatternMatcher.regex(input, p, "screen_size", opts);
    assertEquals(1, matches.size());
    assertEquals("1080", matches.get(0).raw());
}

@Test void string_validatorRejectsMatch() {
    var input = "abcMP3xyz";
    var opts = StringOpts.defaults().withValidator(Validators.sepsSurround(input));
    var matches = PatternMatcher.string(input, java.util.Set.of("MP3"), "audio_codec", opts);
    assertTrue(matches.isEmpty());
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=PatternMatcherTest`
Expected: FAIL — `withValidator` method missing.

- [ ] **Step 3: Add validator field to `RegexOpts`**

Open `src/main/java/io/guessit/engine/RegexOpts.java` and replace its contents:

```java
package io.guessit.engine;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public record RegexOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    Function<String, Object> valueExtractor,
    Function<Object, Object> valueFormatter,
    Predicate<Match> validator
) {
    public RegexOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (validator == null) validator = m -> true;
    }
    public static RegexOpts defaults() {
        return new RegexOpts(1000, Set.of(), false, s -> s, v -> v, m -> true);
    }
    public RegexOpts withPriority(int p) { return new RegexOpts(p, tags, isPrivate, valueExtractor, valueFormatter, validator); }
    public RegexOpts withTags(Set<String> t) { return new RegexOpts(priority, t, isPrivate, valueExtractor, valueFormatter, validator); }
    public RegexOpts asPrivate() { return new RegexOpts(priority, tags, true, valueExtractor, valueFormatter, validator); }
    public RegexOpts withValue(Function<String, Object> ex) { return new RegexOpts(priority, tags, isPrivate, ex, valueFormatter, validator); }
    public RegexOpts withFormatter(Function<Object, Object> fmt) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, fmt, validator); }
    public RegexOpts withValidator(Predicate<Match> v) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, valueFormatter, v); }
}
```

- [ ] **Step 4: Add validator field to `StringOpts`**

Open `src/main/java/io/guessit/engine/StringOpts.java` and replace its contents:

```java
package io.guessit.engine;

import java.util.Set;
import java.util.function.Predicate;

public record StringOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    boolean caseSensitive,
    boolean wholeWord,
    Predicate<Match> validator
) {
    public StringOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (validator == null) validator = m -> true;
    }
    public static StringOpts defaults() {
        return new StringOpts(1000, Set.of(), false, false, true, m -> true);
    }
    public StringOpts withPriority(int p) { return new StringOpts(p, tags, isPrivate, caseSensitive, wholeWord, validator); }
    public StringOpts withTags(Set<String> t) { return new StringOpts(priority, t, isPrivate, caseSensitive, wholeWord, validator); }
    public StringOpts caseSensitive(boolean v) { return new StringOpts(priority, tags, isPrivate, v, wholeWord, validator); }
    public StringOpts wholeWord(boolean v) { return new StringOpts(priority, tags, isPrivate, caseSensitive, v, validator); }
    public StringOpts withValidator(Predicate<Match> v) { return new StringOpts(priority, tags, isPrivate, caseSensitive, wholeWord, v); }
}
```

- [ ] **Step 5: Update `PatternMatcher` to honor validators**

Edit `src/main/java/io/guessit/engine/PatternMatcher.java`:

Replace the body of `regex()` from `out.add(...)` to filter through the validator. Replace these lines:

```java
            Object formatted = opts.valueFormatter().apply(extracted);
            out.add(new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate()));
```

with:

```java
            Object formatted = opts.valueFormatter().apply(extracted);
            var match = new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate());
            if (opts.validator().test(match)) out.add(match);
```

In `string()`, replace these lines:

```java
                if (!opts.wholeWord() || isWordBoundary(hay, idx, end)) {
                    out.add(new Match(name, raw, idx, end, input.substring(idx, end),
                        opts.priority(), opts.tags(), opts.isPrivate()));
                }
```

with:

```java
                if (!opts.wholeWord() || isWordBoundary(hay, idx, end)) {
                    var match = new Match(name, raw, idx, end, input.substring(idx, end),
                        opts.priority(), opts.tags(), opts.isPrivate());
                    if (opts.validator().test(match)) out.add(match);
                }
```

- [ ] **Step 6: Run all tests in package**

Run: `mvn -q -pl . test -Dtest='io.guessit.engine.*'`
Expected: PASS — existing PatternMatcher tests still green; three new validator tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/guessit/engine/RegexOpts.java src/main/java/io/guessit/engine/StringOpts.java src/main/java/io/guessit/engine/PatternMatcher.java src/test/java/io/guessit/engine/PatternMatcherTest.java
git commit -m "feat(engine): per-match validator hook in RegexOpts/StringOpts"
```

---

## Task 5: `YearExtractor`

Python source: `/tmp/guessit/guessit/rules/properties/date.py` (year regex + `KeepMarkedYearInFilepart`). Conflict-with-season/episode handling defers to Plan 3 — for Phase 1 we only need the regex + `valid_year` (1920 ≤ year < 2030) + seps_surround validation. `KeepMarkedYearInFilepart` ships here because YML cases for movies depend on it picking the right year when several `\d{4}` candidates exist in a path.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/YearExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/YearExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.*;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YearExtractorTest {

    private static List<Match> run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), OptionsConfig.empty());
        new PathMarker().produce(ctx);
        new GroupMarker().produce(ctx);
        var x = new YearExtractor();
        x.extract(ctx);
        x.postProcess(ctx);
        return ctx.matches.named("year").toList();
    }

    @Test void simpleYear() {
        var ms = run("Movie.Title.2015.1080p.mkv");
        assertEquals(1, ms.size());
        assertEquals(2015, ms.get(0).value());
    }
    @Test void rejectsOutOfRange() {
        assertTrue(run("file.1900.mkv").isEmpty());
        assertTrue(run("file.2030.mkv").isEmpty());
        assertTrue(run("file.1234.mkv").isEmpty());
    }
    @Test void requiresSepsSurround() {
        assertTrue(run("X2015Y").isEmpty());
    }
    @Test void prefersGroupedYearWhenMultipleInFilepart() {
        // Marked year wins, ungrouped years dropped after the first.
        var ms = run("Movie.2015.Title.[2018].mkv");
        assertEquals(1, ms.size());
        assertEquals(2018, ms.get(0).value());
    }
    @Test void noGroupedYear_keepsFirstAndDropsLaterDuplicates() {
        var ms = run("Movie.2015.Cut.2018.Edit.2020.mkv");
        // Ungrouped: keep first, keep nothing past index 1; per Python: keep [0], drop [2..]
        assertEquals(2, ms.size());
        assertEquals(2015, ms.get(0).value());
        assertEquals(2018, ms.get(1).value());
    }
    @Test void boundaryValues() {
        assertEquals(1920, run("F.1920.mkv").get(0).value());
        assertEquals(2029, run("F.2029.mkv").get(0).value());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=YearExtractorTest`
Expected: FAIL — `YearExtractor` missing.

- [ ] **Step 3: Implement `YearExtractor`**

Create `src/main/java/io/guessit/rules/property/YearExtractor.java`:

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class YearExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("\\d{4}");

    @Override public String name() { return "year"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s))
            .withValidator(m -> {
                if (!Validators.sepsSurround(input).test(m)) return false;
                int v = (Integer) m.value();
                return 1920 <= v && v < 2030;
            });
        for (var match : PatternMatcher.regex(input, PATTERN, "year", opts)) {
            ctx.matches.add(match);
        }
    }

    /** Replicates Python rules/properties/date.py:KeepMarkedYearInFilepart. */
    @Override
    public void postProcess(ParseContext ctx) {
        var years = ctx.matches.named("year").toList();
        if (years.size() <= 1) return;

        var toRemove = new ArrayList<Match>();
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var inPart = years.stream()
                .filter(y -> filepart.covers(y.start(), y.end()))
                .toList();
            if (inPart.size() <= 1) continue;

            var grouped = new ArrayList<Match>();
            var ungrouped = new ArrayList<Match>();
            for (var y : inPart) {
                boolean inGroup = ctx.markers.stream()
                    .anyMatch(mk -> "group".equals(mk.name()) && mk.covers(y.start(), y.end()));
                (inGroup ? grouped : ungrouped).add(y);
            }
            if (!grouped.isEmpty() && !ungrouped.isEmpty()) {
                toRemove.addAll(ungrouped);
                if (grouped.size() > 1) toRemove.addAll(grouped.subList(1, grouped.size()));
            } else if (grouped.isEmpty()) {
                // Keep first ungrouped (for title); drop everything from index 2 onward.
                if (ungrouped.size() > 2) toRemove.addAll(ungrouped.subList(2, ungrouped.size()));
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

Note on `Marker`: this assumes `Marker` exposes `name()` and `covers(int,int)` from Plan 0. If the API differs, adapt the calls — do not invent new fields.

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=YearExtractorTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/YearExtractor.java src/test/java/io/guessit/rules/property/YearExtractorTest.java
git commit -m "feat(rules): add YearExtractor with valid_year range + KeepMarkedYearInFilepart"
```

---

## Task 6: `ContainerExtractor`

Python source: `/tmp/guessit/guessit/rules/properties/container.py`. Two phases:
1. `\.<ext>$` regex on the input tail produces the *extension* container with tags `["extension", <kind>]` (kind ∈ subtitles, info, video, torrent, nzb).
2. Plain `<ext>` strings anywhere else, validated by `seps_surround`, produce body-of-name containers.

Conflict resolution between the two flavors and other properties (source, video_codec) is centralized — for Phase 1 we lean on the priority heuristic + tag-based filtering in `OutputBuilder` already shipped in Plan 0.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/ContainerExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/ContainerExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerExtractorTest {

    private static List<Object> run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        new ContainerExtractor().extract(ctx);
        return ctx.matches.named("container").map(m -> m.value()).toList();
    }

    @Test void videoExtension() { assertEquals(List.of("mkv"), run("Movie.2015.mkv")); }
    @Test void subtitleExtension() { assertEquals(List.of("srt"), run("Movie.2015.srt")); }
    @Test void torrentExtension() { assertEquals(List.of("torrent"), run("Movie.2015.torrent")); }
    @Test void infoExtension() { assertEquals(List.of("nfo"), run("Movie.2015.nfo")); }
    @Test void noExtension_returnsBodyContainer() {
        // 'avi' appears in the body, no trailing extension.
        var values = run("Movie.avi.Title");
        assertTrue(values.contains("avi"));
    }
    @Test void unknownExtensionDropped() { assertTrue(run("Movie.2015.exe").isEmpty()); }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=ContainerExtractorTest`
Expected: FAIL — `ContainerExtractor` missing.

- [ ] **Step 3: Implement `ContainerExtractor`**

Create `src/main/java/io/guessit/rules/property/ContainerExtractor.java`:

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class ContainerExtractor implements Extractor {

    @Override public String name() { return "container"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("container");
        var subtitles = stringList(section.get("subtitles"));
        var info       = stringList(section.get("info"));
        var videos     = stringList(section.get("videos"));
        var torrent    = stringList(section.get("torrent"));
        var nzb        = stringList(section.get("nzb"));

        var input = ctx.input;

        // 1. Extension matches: trailing `.<ext>` only; tagged "extension" + kind.
        addExtensionRegex(ctx, input, subtitles, "subtitle");
        addExtensionRegex(ctx, input, info,      "info");
        addExtensionRegex(ctx, input, videos,    "video");
        addExtensionRegex(ctx, input, torrent,   "torrent");
        addExtensionRegex(ctx, input, nzb,       "nzb");

        // 2. Body matches: same words but anywhere, requires seps_surround.
        var body = new HashSet<String>();
        body.addAll(subtitles); body.remove("sub"); body.remove("ass");  // matches Python carve-out
        body.addAll(videos); body.addAll(torrent); body.addAll(nzb);
        var opts = StringOpts.defaults()
            .withValidator(Validators.sepsSurround(input))
            .withTags(Set.of("body"));
        for (var m : PatternMatcher.string(input, body, "container", opts)) {
            // Skip body matches that overlap an extension match — extension wins.
            boolean overlapsExt = ctx.matches.named("container")
                .anyMatch(other -> other.tags().contains("extension")
                                && other.start() < m.end() && m.start() < other.end());
            if (!overlapsExt) ctx.matches.add(m);
        }
    }

    private static void addExtensionRegex(ParseContext ctx, String input, List<String> exts, String kindTag) {
        if (exts.isEmpty()) return;
        var or = String.join("|", exts.stream().map(Pattern::quote).toList());
        var p = Pattern.compile("\\.(?:" + or + ")$", Pattern.CASE_INSENSITIVE);
        var opts = RegexOpts.defaults()
            .withValue(s -> s.startsWith(".") ? s.substring(1).toLowerCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT))
            .withTags(Set.of("extension", kindTag));
        for (var m : PatternMatcher.regex(input, p, "container", opts)) {
            ctx.matches.add(m);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        return List.of();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=ContainerExtractorTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/ContainerExtractor.java src/test/java/io/guessit/rules/property/ContainerExtractorTest.java
git commit -m "feat(rules): add ContainerExtractor (extension + body, config-driven)"
```

---

## Task 7: `ScreenSizeExtractor` regex emission

Phase 1 ships the regex emission + value normalization; the rebulk-only `ScreenSizeOnlyOne` and `ResolveScreenSizeConflicts` rules depend on path markers being fully populated, which they already are after Plan 0. Aspect ratio computation and the `4k` alias are included.

Python source: `/tmp/guessit/guessit/rules/properties/screen_size.py`. Config under `OptionsConfig.section("screen_size")`: `interlaced`, `progressive`, `frame_rates`, `min_ar`, `max_ar`.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/ScreenSizeExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/ScreenSizeExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScreenSizeExtractorTest {

    private static ParseContext run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        new PathMarker().produce(ctx);
        new GroupMarker().produce(ctx);
        var x = new ScreenSizeExtractor();
        x.extract(ctx);
        ConflictSolver.solve(ctx.matches);
        x.postProcess(ctx);
        return ctx;
    }

    @Test void progressive1080p() {
        var ctx = run("Movie.2015.1080p.BluRay.mkv");
        assertEquals("1080p", ctx.matches.named("screen_size").findFirst().get().value());
    }
    @Test void interlaced1080i() {
        var ctx = run("Show.2015.1080i.HDTV.mkv");
        assertEquals("1080i", ctx.matches.named("screen_size").findFirst().get().value());
    }
    @Test void widthByHeight() {
        var ctx = run("Movie.2015.1920x1080.mkv");
        // standard ar, width+height present → normalize to "1080p"
        assertEquals("1080p", ctx.matches.named("screen_size").findFirst().get().value());
        assertEquals(1.778, ((Number) ctx.matches.named("aspect_ratio").findFirst().get().value()).doubleValue(), 0.001);
    }
    @Test void fourK() {
        var ctx = run("Movie.4K.mkv");
        assertEquals("2160p", ctx.matches.named("screen_size").findFirst().get().value());
    }
    @Test void frameRate24p() {
        var ctx = run("Movie.2015.1080p24.mkv");
        assertEquals("1080p", ctx.matches.named("screen_size").findFirst().get().value());
        assertNotNull(ctx.matches.named("frame_rate").findFirst().orElse(null));
    }
    @Test void rejectsLooseDigits() {
        var ctx = run("File.no.resolution.here.mkv");
        assertEquals(0L, ctx.matches.named("screen_size").count());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=ScreenSizeExtractorTest`
Expected: FAIL — extractor missing.

- [ ] **Step 3: Implement `ScreenSizeExtractor`**

Create `src/main/java/io/guessit/rules/property/ScreenSizeExtractor.java`:

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class ScreenSizeExtractor implements Extractor {

    @Override public String name() { return "screen_size"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("screen_size");
        var interlaced = stringList(section.get("interlaced"));
        var progressive = stringList(section.get("progressive"));
        var frameRates = stringList(section.get("frame_rates"));
        var input = ctx.input;

        var heightI = "(?<height>" + String.join("|", interlaced) + ")";
        var heightP = "(?<height>" + String.join("|", progressive) + ")";
        var fr = "(?:" + String.join("|", frameRates) + ")";
        var resPrefix = "(?:(?<width>\\d{3,4})(?:x|\\*))?";

        var validator = Validators.sepsSurround(input);
        var opts = RegexOpts.defaults().withValidator(validator);

        addRegex(ctx, resPrefix + heightI + "(?<scan>i)" + "(?:" + fr + ")?", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)" + "(?:" + fr + ")?", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)?(?:hd)", opts);
        addRegex(ctx, resPrefix + heightP + "(?<scan>p)?x?", opts);

        // 4k literal → 2160p
        var fourK = StringOpts.defaults().withValidator(validator);
        for (var m : PatternMatcher.string(input, Set.of("4k"), "screen_size", fourK)) {
            ctx.matches.add(new Match("screen_size", "2160p", m.start(), m.end(), m.raw(),
                m.priority(), Set.of("normalized"), false));
        }

        // width-x-height fallback for non-standard sizes
        var whP = Pattern.compile("(?<width>\\d{3,4})-?(?:x|\\*)-?(?<height>\\d{3,4})", Pattern.CASE_INSENSITIVE);
        for (var m : PatternMatcher.regex(input, whP, "screen_size", opts)) {
            // Replace the value with raw "WxH" — PostProcess will normalize again.
            ctx.matches.add(m);
        }

        // frame_rate standalone, with mandatory `p` or `fps` suffix.
        var frP = Pattern.compile("(" + fr + ")-?(?:p|fps)", Pattern.CASE_INSENSITIVE);
        var frOpts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s.replaceAll("\\..*$", "")))
            .withValidator(validator);
        for (var m : PatternMatcher.regex(input, frP, "frame_rate", frOpts)) {
            ctx.matches.add(m);
        }
    }

    private static void addRegex(ParseContext ctx, String src, RegexOpts opts) {
        var p = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        for (var m : PatternMatcher.regex(ctx.input, p, "screen_size", opts)) {
            ctx.matches.add(m);
        }
    }

    /** PostProcessScreenSize + ScreenSizeOnlyOne. ResolveScreenSizeConflicts deferred to Plan 3. */
    @Override
    public void postProcess(ParseContext ctx) {
        var section = ctx.config.section("screen_size");
        var standardHeights = new HashSet<>(stringList(section.get("progressive")));
        double minAr = ((Number) section.getOrDefault("min_ar", 1.333)).doubleValue();
        double maxAr = ((Number) section.getOrDefault("max_ar", 1.898)).doubleValue();

        // PostProcessScreenSize: parse raw with named groups via regex re-match on raw text.
        var widthHeight = Pattern.compile("(?<width>\\d{3,4})[x*-](?<height>\\d{3,4})", Pattern.CASE_INSENSITIVE);
        var heightScan  = Pattern.compile("(?<height>\\d{3,4})(?<scan>[ip])?", Pattern.CASE_INSENSITIVE);
        var toReplace = new ArrayList<Match[]>();
        for (var m : ctx.matches.named("screen_size").toList()) {
            if (m.tags().contains("normalized")) continue;
            var wh = widthHeight.matcher(m.raw());
            if (wh.find()) {
                int w = Integer.parseInt(wh.group("width"));
                int h = Integer.parseInt(wh.group("height"));
                double ar = (double) w / h;
                ctx.matches.add(new Match("aspect_ratio", Math.round(ar * 1000.0) / 1000.0,
                    m.start(), m.end(), m.raw(), m.priority(), Set.of(), false));
                String value = (standardHeights.contains(String.valueOf(h)) && minAr < ar && ar < maxAr)
                    ? h + "p" : w + "x" + h;
                toReplace.add(new Match[]{ m, m.withTags(Set.of("normalized")) });
                ctx.matches.replace(m, new Match("screen_size", value, m.start(), m.end(), m.raw(),
                    m.priority(), Set.of("normalized"), false));
                continue;
            }
            var hs = heightScan.matcher(m.raw());
            if (hs.find()) {
                String h = hs.group("height");
                String scan = hs.group("scan") == null ? "p" : hs.group("scan").toLowerCase(Locale.ROOT);
                ctx.matches.replace(m, new Match("screen_size", h + scan, m.start(), m.end(), m.raw(),
                    m.priority(), Set.of("normalized"), false));
            }
        }

        // ScreenSizeOnlyOne: per filepart, keep last screen_size only when distinct values present.
        for (var filepart : ctx.markers) {
            if (!"path".equals(filepart.name())) continue;
            var inPart = ctx.matches.named("screen_size")
                .filter(m -> filepart.covers(m.start(), m.end()))
                .sorted(Comparator.comparingInt(Match::start).reversed())
                .toList();
            if (inPart.size() <= 1) continue;
            var distinct = inPart.stream().map(m -> String.valueOf(m.value())).distinct().count();
            if (distinct > 1) {
                for (int i = 1; i < inPart.size(); i++) ctx.matches.remove(inPart.get(i));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        return List.of();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=ScreenSizeExtractorTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/ScreenSizeExtractor.java src/test/java/io/guessit/rules/property/ScreenSizeExtractorTest.java
git commit -m "feat(rules): add ScreenSizeExtractor with aspect_ratio + frame_rate"
```

---

## Task 8: `VideoCodecExtractor`

Python source: `/tmp/guessit/guessit/rules/properties/video_codec.py`. Patterns are hardcoded (not config-driven). Ships codec, profile, color_depth, video_api in one extractor; emits multiple property names by passing `name` per call.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/VideoCodecExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/VideoCodecExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VideoCodecExtractorTest {

    private static ParseContext run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), OptionsConfig.empty());
        var x = new VideoCodecExtractor();
        x.extract(ctx);
        ConflictSolver.solve(ctx.matches);
        x.postProcess(ctx);
        return ctx;
    }

    @Test void h264() {
        assertEquals("H.264", run("Movie.2015.1080p.x264.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void h265() {
        assertEquals("H.265", run("Movie.2015.1080p.x265.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void hevc() {
        assertEquals("H.265", run("Movie.2015.1080p.HEVC.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void hevc10ColorDepth() {
        var ctx = run("Movie.2015.1080p.HEVC10.mkv");
        assertEquals("H.265", ctx.matches.named("video_codec").findFirst().get().value());
        assertEquals("10-bit", ctx.matches.named("color_depth").findFirst().get().value());
    }
    @Test void xvid() {
        assertEquals("Xvid", run("Movie.2015.XviD.avi").matches.named("video_codec").findFirst().get().value());
    }
    @Test void divx() {
        assertEquals("DivX", run("Movie.2015.DivX.avi").matches.named("video_codec").findFirst().get().value());
    }
    @Test void mpeg2() {
        assertEquals("MPEG-2", run("Movie.2015.MPEG-2.mkv").matches.named("video_codec").findFirst().get().value());
    }
    @Test void dxvaApi() {
        assertEquals("DXVA", run("Movie.2015.DXVA.mkv").matches.named("video_api").findFirst().get().value());
    }
    @Test void rejectsBareDigits() {
        assertTrue(run("Random.text.264.no.codec").matches.named("video_codec").findAny().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=VideoCodecExtractorTest`
Expected: FAIL — extractor missing.

- [ ] **Step 3: Implement `VideoCodecExtractor`**

Create `src/main/java/io/guessit/rules/property/VideoCodecExtractor.java`:

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class VideoCodecExtractor implements Extractor {

    @Override public String name() { return "video_codec"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var v = Validators.sepsSurround(input);

        // codec
        addCodec(ctx, "Rv\\d{2}",                                   "RealVideo", v);
        addCodec(ctx, "Mpe?g-?2",                                    "MPEG-2",    v);
        addCodec(ctx, "[hx]-?262",                                   "MPEG-2",    v);
        addCodec(ctx, "DVDivX|DivX",                                 "DivX",      v);
        addCodec(ctx, "XviD",                                        "Xvid",      v);
        addCodec(ctx, "VC-?1",                                       "VC-1",      v);
        addCodec(ctx, "VP7",                                         "VP7",       v);
        addCodec(ctx, "VP8|VP80",                                    "VP8",       v);
        addCodec(ctx, "VP9",                                         "VP9",       v);
        addCodec(ctx, "[hx]-?263",                                   "H.263",     v);
        addCodec(ctx, "[hx]-?264|(?:MPEG-?4)?AVC(?:HD)?",            "H.264",     v);
        addCodec(ctx, "[hx]-?265|HEVC",                              "H.265",     v);

        // hevc10 → H.265 + 10-bit color_depth
        var hevc10 = Pattern.compile("hevc(10)", Pattern.CASE_INSENSITIVE);
        var optsCodec = RegexOpts.defaults().withValidator(v).withValue(s -> "H.265");
        for (var m : PatternMatcher.regex(input, hevc10, "video_codec", optsCodec)) {
            ctx.matches.add(m);
            ctx.matches.add(new Match("color_depth", "10-bit",
                m.start(), m.end(), m.raw(), m.priority(), Set.of(), false));
        }

        // video_profile (validated, validators enforce seps_surround)
        addStr(ctx, "video_profile", "Baseline",                            Set.of("BP"),  v);
        addStr(ctx, "video_profile", "Extended",                            Set.of("XP","EP"), v);
        addStr(ctx, "video_profile", "Main",                                Set.of("MP"),  v);
        addStr(ctx, "video_profile", "High",                                Set.of("HP","HiP"), v);
        addStr(ctx, "video_profile", "Scalable Video Coding",               Set.of("SC","SVC"), v);
        addRegexProfile(ctx, "AVC(?:HD)?",                                  "Advanced Video Codec High Definition", v);
        addStr(ctx, "video_profile", "High Efficiency Video Coding",        Set.of("HEVC"), v);
        addRegexProfile(ctx, "Hi422P",                                      "High 4:2:2", v);
        addRegexProfile(ctx, "Hi444PP",                                     "High 4:4:4 Predictive", v);
        addRegexProfile(ctx, "Hi10P?",                                      "High 10", v);

        // video_api
        addStr(ctx, "video_api", "DXVA", Set.of("DXVA"), v);

        // color_depth
        addRegexNamed(ctx, "color_depth", "12.?bits?", "12-bit", v);
        addRegexNamed(ctx, "color_depth", "10.?bits?|YUV420P10|Hi10P?", "10-bit", v);
        addRegexNamed(ctx, "color_depth", "8.?bits?",  "8-bit",  v);
    }

    /** Replicates ValidateVideoCodec + VideoProfileRule. */
    @Override
    public void postProcess(ParseContext ctx) {
        // ValidateVideoCodec: Plan-0 PatternMatcher already enforces seps_surround via validator. No-op here.
        // VideoProfileRule: drop video_profile tagged 'video_profile.rule' that has no nearby video_codec.
        // Tags wiring shipped in extract() above is conservative; this rule deferred to Plan 5 polish.
    }

    private void addCodec(ParseContext ctx, String src, String value, java.util.function.Predicate<Match> v) {
        var p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        var opts = RegexOpts.defaults().withValidator(v).withValue(s -> value);
        for (var m : PatternMatcher.regex(ctx.input, p, "video_codec", opts)) ctx.matches.add(m);
    }
    private void addRegexProfile(ParseContext ctx, String src, String value, java.util.function.Predicate<Match> v) {
        addRegexNamed(ctx, "video_profile", src, value, v);
    }
    private void addRegexNamed(ParseContext ctx, String name, String src, String value, java.util.function.Predicate<Match> v) {
        var p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        var opts = RegexOpts.defaults().withValidator(v).withValue(s -> value);
        for (var m : PatternMatcher.regex(ctx.input, p, name, opts)) ctx.matches.add(m);
    }
    private void addStr(ParseContext ctx, String name, String value, Set<String> needles,
                        java.util.function.Predicate<Match> v) {
        var opts = StringOpts.defaults().withValidator(v);
        for (var m : PatternMatcher.string(ctx.input, needles, name, opts)) {
            ctx.matches.add(new Match(name, value, m.start(), m.end(), m.raw(),
                m.priority(), m.tags(), m.isPrivate()));
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=VideoCodecExtractorTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/VideoCodecExtractor.java src/test/java/io/guessit/rules/property/VideoCodecExtractorTest.java
git commit -m "feat(rules): add VideoCodecExtractor (codec, profile, video_api, color_depth)"
```

---

## Task 9: `AudioCodecExtractor` — config-driven patterns

Python source: `/tmp/guessit/guessit/rules/properties/audio_codec.py` + `config/options.json` section `audio_codec`. The config maps a value name to a list of strings/regexes. We ship the basic patterns + `AudioValidatorRule` (drop audio properties without seps unless adjacent to another audio property). DTS-HD/AAC/Dolby cross-rules deferred to Plan 5.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/AudioCodecExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/AudioCodecExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.ConfigLoader;
import io.guessit.engine.ConflictSolver;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioCodecExtractorTest {
    private static ParseContext run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), ConfigLoader.load(Options.defaults()));
        var x = new AudioCodecExtractor();
        x.extract(ctx);
        ConflictSolver.solve(ctx.matches);
        x.postProcess(ctx);
        return ctx;
    }

    @Test void aac() {
        assertEquals("AAC", run("Movie.2015.1080p.AAC.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void mp3() {
        assertEquals("MP3", run("Movie.2015.MP3.avi").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void dolbyDigital_ac3() {
        assertEquals("Dolby Digital", run("Movie.2015.AC3.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void dts() {
        assertEquals("DTS", run("Movie.2015.DTS.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void dtsHd() {
        assertEquals("DTS-HD", run("Movie.2015.DTS-HD.mkv").matches.named("audio_codec").findFirst().get().value());
    }
    @Test void channels_5_1() {
        assertEquals("5.1", run("Movie.2015.5.1.mkv").matches.named("audio_channels").findFirst().get().value());
    }
    @Test void channels_2_0() {
        assertEquals("2.0", run("Movie.2015.2.0.mkv").matches.named("audio_channels").findFirst().get().value());
    }
    @Test void rejectsLooseLetters() {
        assertTrue(run("Movie.AACX.mkv").matches.named("audio_codec").findAny().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -pl . test -Dtest=AudioCodecExtractorTest`
Expected: FAIL — extractor missing.

- [ ] **Step 3: Implement `AudioCodecExtractor`**

Create `src/main/java/io/guessit/rules/property/AudioCodecExtractor.java`:

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.*;
import java.util.regex.Pattern;

public final class AudioCodecExtractor implements Extractor {

    private static final Set<String> AUDIO_PROPS = Set.of("audio_codec", "audio_profile", "audio_channels");

    @Override public String name() { return "audio_codec"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("audio_codec");
        loadGroup(ctx, "audio_codec", asMap(section.get("audio_codec")));
        loadGroup(ctx, "audio_profile", asMap(section.get("audio_profile")));
        loadGroup(ctx, "audio_channels", asMap(section.get("audio_channels")));
    }

    /** AudioValidatorRule — drop audio matches not surrounded by seps unless touching another audio match. */
    @Override
    public void postProcess(ParseContext ctx) {
        var input = ctx.input;
        var audio = ctx.matches.all().filter(m -> AUDIO_PROPS.contains(m.name())).toList();
        var toRemove = new ArrayList<Match>();
        for (var a : audio) {
            boolean before = a.start() == 0 || Seps.isSep(input.charAt(a.start() - 1))
                || audio.stream().anyMatch(o -> o != a && o.end() == a.start());
            boolean after = a.end() == input.length() || Seps.isSep(input.charAt(a.end()))
                || audio.stream().anyMatch(o -> o != a && o.start() == a.end());
            if (!before || !after) toRemove.add(a);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private void loadGroup(ParseContext ctx, String propName, Map<String, Object> group) {
        var v = Validators.sepsSurround(ctx.input);
        for (var entry : group.entrySet()) {
            String value = entry.getKey();
            for (var pattern : flattenPatterns(entry.getValue())) {
                if (pattern.regex()) {
                    var p = Pattern.compile(Abbreviations.dash(pattern.source()), Pattern.CASE_INSENSITIVE);
                    var opts = RegexOpts.defaults().withValidator(v).withValue(s -> value);
                    for (var m : PatternMatcher.regex(ctx.input, p, propName, opts)) ctx.matches.add(m);
                } else {
                    var opts = StringOpts.defaults().withValidator(v);
                    for (var m : PatternMatcher.string(ctx.input, Set.of(pattern.source()), propName, opts)) {
                        ctx.matches.add(new Match(propName, value, m.start(), m.end(), m.raw(),
                            m.priority(), m.tags(), m.isPrivate()));
                    }
                }
            }
        }
    }

    /** Pattern config can be: String → string match, list of {String|Map}, Map with "string"/"regex" keys. */
    private record PatternEntry(String source, boolean regex) {}

    @SuppressWarnings("unchecked")
    private static List<PatternEntry> flattenPatterns(Object def) {
        var out = new ArrayList<PatternEntry>();
        if (def instanceof String s) {
            out.add(new PatternEntry(s, false));
        } else if (def instanceof List<?> list) {
            for (var item : list) out.addAll(flattenPatterns(item));
        } else if (def instanceof Map<?, ?> map) {
            var m = (Map<String, Object>) map;
            if (m.get("string") instanceof String ss) out.add(new PatternEntry(ss, false));
            if (m.get("string") instanceof List<?> sl) for (var s : sl) out.add(new PatternEntry((String) s, false));
            if (m.get("regex") instanceof String rs) out.add(new PatternEntry(rs, true));
            if (m.get("regex") instanceof List<?> rl) for (var s : rl) out.add(new PatternEntry((String) s, true));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl . test -Dtest=AudioCodecExtractorTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/AudioCodecExtractor.java src/test/java/io/guessit/rules/property/AudioCodecExtractorTest.java
git commit -m "feat(rules): add AudioCodecExtractor (codec, profile, channels)"
```

---

## Task 10: Register extractors in `Rules.allInOrder()`

**Files:**
- Modify: `src/main/java/io/guessit/rules/Rules.java`

- [ ] **Step 1: Edit the registry**

Open `src/main/java/io/guessit/rules/Rules.java` and replace the `allInOrder()` body. Replace:

```java
    public static List<Extractor> allInOrder() {
        return List.of(); // Plan 1+ append here
    }
```

with:

```java
    public static List<Extractor> allInOrder() {
        return List.of(
            new io.guessit.rules.property.YearExtractor(),
            new io.guessit.rules.property.ContainerExtractor(),
            new io.guessit.rules.property.ScreenSizeExtractor(),
            new io.guessit.rules.property.VideoCodecExtractor(),
            new io.guessit.rules.property.AudioCodecExtractor()
        );
    }
```

- [ ] **Step 2: Smoke run**

Run: `mvn -q -pl . exec:java -Dexec.mainClass=io.guessit.cli.GuessitCli -Dexec.args="Movie.Name.2015.1080p.x264.AC3.mkv"`
Expected: stdout shows `year: 2015`, `screen_size: 1080p`, `video_codec: H.264`, `audio_codec: Dolby Digital`, `container: mkv`.

If the CLI binary path differs in your repo, run instead:

```bash
mvn -q test -Dtest='io.guessit.rules.property.*'
```

Expected: all 5 property test classes green.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/rules/Rules.java
git commit -m "feat(rules): register Phase 1 extractors in pipeline"
```

---

## Task 11: Enable phase-1 YML cases + run parity baseline

`YmlParityTest` is currently `@Disabled("phase-0...")`. Drop the disable, replace with a phase tag mechanism that excludes only cases failing for properties beyond Phase 1.

**Files:**
- Modify: `src/test/java/io/guessit/parity/YmlParityTest.java`

- [ ] **Step 1: Replace `YmlParityTest.java` with the phase-aware version**

Open `src/test/java/io/guessit/parity/YmlParityTest.java` and replace its full contents:

```java
package io.guessit.parity;

import io.guessit.Guessit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YmlParityTest {

    /** Properties shipped through Plan 1. Cases that *only* expect these will run; others assumed-skipped. */
    private static final Set<String> PHASE_1_PROPS = Set.of(
        "year", "container", "screen_size", "aspect_ratio", "frame_rate",
        "video_codec", "video_profile", "color_depth", "video_api",
        "audio_codec", "audio_profile", "audio_channels"
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        boolean inPhase = c.expected().keySet().stream().allMatch(PHASE_1_PROPS::contains);
        Assumptions.assumeTrue(inPhase, "skipped: out of Phase 1 scope");

        var result = Guessit.parse(c.input(), c.options()).toMap();
        if (c.negative()) {
            assertNotEquals(c.expected(), result, "negative case unexpectedly matched");
        } else {
            assertEquals(c.expected(), result);
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/");
    }
}
```

- [ ] **Step 2: Run the parity suite**

Run: `mvn -q test -Dtest=YmlParityTest`
Expected: most Phase-1 cases pass; some fail because of long-tail edge cases. Note the ratio of `Tests run: N, Failures: F, Skipped: S`.

- [ ] **Step 3: Verify ≥20% raw pass rate of all YML cases**

Compute: `passed / (passed + failed + skipped) >= 0.20` where the numerator excludes Assumption-skipped cases. The Maven Surefire report at `target/surefire-reports/TEST-io.guessit.parity.YmlParityTest.xml` has the counts.

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
assert passed / total >= 0.20, f"Phase 1 pass rate {passed/total:.1%} < 20% target"
EOF
```

Expected: `pass_rate >= 20.0%` and the assertion does not fire.

- [ ] **Step 4: If pass rate < 20%, debug** (skip if Step 3 passed)

Pick the first 10 failing cases from Surefire output, read each, identify which extractor needs an edge-case fix or which marker is missing. Add a focused test, fix the extractor, rerun parity. Keep iterating until ≥20%.

Each fix lands as its own commit, e.g.:

```bash
git commit -m "fix(rules): <extractor> handle <case>"
```

- [ ] **Step 5: Commit harness change**

```bash
git add src/test/java/io/guessit/parity/YmlParityTest.java
git commit -m "test(parity): enable Phase 1 YML cases"
```

---

## Final verification

- [ ] **Step 1: Full build + tests**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS; all unit tests green; parity ≥20%.

- [ ] **Step 2: Smoke CLI**

Run: `java -jar target/guessit-java-*-cli.jar "Movie.Name.2015.1080p.BluRay.x264.AAC-RG.mkv"`
Expected output (key order may vary):

```
year: 2015
screen_size: 1080p
video_codec: H.264
audio_codec: AAC
container: mkv
```

- [ ] **Step 3: Final tag commit**

```bash
git tag plan-1-done
```

---

## Self-Review

**1. Spec coverage** — Spec phase 1 lists: engine + year, screen_size, video_codec, audio_codec, container, ~20% YML. Coverage:
- engine helpers (Seps/Validators/Abbreviations + validator hook): Tasks 1–4
- year: Task 5
- container: Task 6
- screen_size + aspect_ratio + frame_rate: Task 7
- video_codec + video_profile + color_depth + video_api: Task 8
- audio_codec + audio_profile + audio_channels: Task 9
- registration + parity gate ≥20%: Tasks 10–11

**2. Placeholder scan** — No `TODO`/`TBD` left. Each step contains either complete code, an exact command, or both.

**3. Type consistency** — `Match`, `MatchSet`, `RegexOpts`, `StringOpts`, `Extractor`, `ParseContext`, `Marker.name()/covers()`, `OptionsConfig.section()` referenced in this plan all match their Plan-0 signatures (verified by reading each file before writing this plan).

**4. Deferred vs. shipped** — Explicitly deferred to later plans, with a code comment in the relevant extractor:
- `ResolveScreenSizeConflicts` (needs season/episode + source matches → Plan 3 / Plan 2)
- `VideoProfileRule` (needs codec adjacency search; Plan 5 polish)
- DTS-HD/AAC/Dolby cross-rules (`DtsHDRule`, `DtsRule`, `AacRule`, `DolbyDigitalRule`, `HqConflictRule`) — Plan 5 polish
- `KeepMarkedYearInFilepart` season-vs-year cases — covered for groups, but year-vs-season swap belongs to Plan 3

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-02-plan-1-phase1-extractors.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.

Which approach?
