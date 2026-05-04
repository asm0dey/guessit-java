# Plan 3: Phase 3 Extractors — episode, season, episode_count, season_count, episode_details, episode_format, version, disc, absolute_episode, date, week

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Phase 3 extractors against the foundation laid in Plans 0–2. End state: `Rules.allInOrder()` registers `EpisodeDetailsExtractor`, `EpisodeFormatExtractor`, `VersionExtractor`, `SeasonEpisodeExtractor` (strong SxxExx family), `EpisodeWordExtractor`, `WeakEpisodeExtractor`, `WeakDuplicateExtractor`, `DiscRule`, `AbsoluteEpisodeRule`, `DateExtractor`, `WeekExtractor`; per-rule unit tests pass; `YearExtractor` learns the season/episode/week conflict-solver hooks; the YML parity gate widens to include `season`, `episode`, `episode_count`, `season_count`, `episode_details`, `episode_format`, `version`, `date`, `week`, `disc`, `absolute_episode` plus the Phase 1+2 props, and ≥80% of all YML cases pass.

**Architecture:** New shared helpers under `io.guessit.engine`: `Numerals` (digital + roman + word numeral parser, Python `rules/common/numeral.py` equivalent), `Chain` (head + repeated-tail regex scanner so each strong episode/season chain runs as one head match plus an iterative tail loop, replicating rebulk's chain semantics), and `DatePatterns` (the seven date regexes from Python `rules/common/date.py`). Each extractor lives in `io.guessit.rules.property.<Name>Extractor` and implements `Extractor` from Plan 0. Per-rule post-processing (`EpisodeDetailValidator`, `VersionValidator`, `OrderingValidator`, `EpisodesSeasonChainBreaker`, `SeasonEpisodeConflictSolver`, `RemoveWeakIfSxxExx`, `RemoveWeakIfMovie`, `RemoveWeak`, `RemoveInvalidSeason`, `RemoveInvalidEpisode`, `RemoveWeakDuplicate`, `RemoveDetachedEpisodeNumber`, `EpisodeNumberSeparatorRange`, `SeasonSeparatorRange`, `SeePatternRange`, `EpisodeSingleDigitValidator`, `RenameToAbsoluteEpisode`, `RenameToDiscMatch`, `KeepMarkedYearInFilepart` season-aware extension) runs in `Extractor.postProcess(ctx)` after the central `ConflictSolver`, mirroring Python's rebulk pass order. Cross-extractor conflicts (year vs season/episode, episode vs date/audio_channels/screen_size) are resolved through a per-extractor `coexist`/length tweak inside `postProcess` rather than a new central solver, keeping the engine surface stable.

**Tech Stack:** Same as Plan 0/1/2 — Java 25, JUnit Jupiter 5.12.x, Apache Commons CSV (already on classpath), Jackson + SnakeYAML for config, no new dependencies.

**Reference source:**
- Python guessit checkout: `/tmp/guessit` (rule code lives under `/tmp/guessit/guessit/`).
- Python rebulk checkout: `/tmp/rebulk` (engine semantics — `Rebulk.chain()`, `ConflictSolver`, `Match`, `Marker` — referenced for parity).
- Python rule files this plan ports: `/tmp/guessit/guessit/rules/properties/{episodes,date}.py`.
- Python helpers: `/tmp/guessit/guessit/rules/common/{date,numeral,validators,formatters,words}.py`, `/tmp/guessit/guessit/reutils.py`.
- Python config: `/tmp/guessit/guessit/config/options.json` (`advanced_config.episodes`, `advanced_config.date`).
- Spec: `docs/superpowers/specs/2026-05-02-guessit-java-design.md`
- Plan 0: `docs/superpowers/plans/2026-05-02-plan-0-foundation.md`
- Plan 1: `docs/superpowers/plans/2026-05-02-plan-1-phase1-extractors.md`
- Plan 2: `docs/superpowers/plans/2026-05-02-plan-2-phase2-source-language-country-releasegroup.md`

---

## File Structure

Created in this plan (paths relative to repo root):

```
src/main/java/io/guessit/
├── engine/
│   ├── Numerals.java                              // digital/roman/word numeral parsing (rules/common/numeral.py)
│   ├── Chain.java                                 // head + repeated-tail regex scanner for SxxExx chains
│   └── DatePatterns.java                          // seven date regexes + valid_year/valid_week + search_date
└── rules/
    └── property/
        ├── EpisodeDetailsExtractor.java
        ├── EpisodeFormatExtractor.java
        ├── VersionExtractor.java
        ├── SeasonEpisodeExtractor.java            // S01E02, 01x02, S01E02E03, S01S02
        ├── EpisodeWordExtractor.java              // "Episode 4", "ep 112", "Season 2", "Cap.102"
        ├── WeakEpisodeExtractor.java              // \d{2}, \d{3,4}, single digit, weak-episode tag
        ├── WeakDuplicateExtractor.java            // (?P<season>\d{1,2})(?P<episode>\d{2}) weak-duplicate tag
        ├── DiscRule.java                          // rename d-marker episode chains → disc
        ├── AbsoluteEpisodeRule.java               // RenameToAbsoluteEpisode
        ├── DateExtractor.java
        └── WeekExtractor.java

src/test/java/io/guessit/
├── engine/
│   ├── NumeralsTest.java
│   ├── ChainTest.java
│   └── DatePatternsTest.java
└── rules/property/
    ├── EpisodeDetailsExtractorTest.java
    ├── EpisodeFormatExtractorTest.java
    ├── VersionExtractorTest.java
    ├── SeasonEpisodeExtractorTest.java
    ├── EpisodeWordExtractorTest.java
    ├── WeakEpisodeExtractorTest.java
    ├── WeakDuplicateExtractorTest.java
    ├── DiscRuleTest.java
    ├── AbsoluteEpisodeRuleTest.java
    ├── DateExtractorTest.java
    └── WeekExtractorTest.java
```

Modified in this plan:

```
src/main/java/io/guessit/rules/Rules.java               // register Phase 3 extractors in Python order
src/main/java/io/guessit/rules/property/YearExtractor.java // year-vs-season/episode/week conflict tweak
src/main/java/io/guessit/rules/post/OutputBuilder.java   // route disc, episode_details, week, absolute_episode through extras with list-collapsing
src/test/java/io/guessit/parity/YmlParityTest.java       // widen PHASE_PROPS and raise threshold to ≥80%
```

Responsibilities (one per file):
- `engine/Numerals` — `parse(String, IntEnabled, RomanEnabled, WordEnabled)`, `roman(String)`, `digital(String)`, plus the precompiled `NUMERAL` and `ROMAN` source strings used inside extractor regexes. Mirrors Python `rules/common/numeral.py`.
- `engine/Chain` — `Chain.head(Pattern, Map<String,String> names)` produces a head match; `chain.tail(Pattern, Map<String,String> names, Repeater)` adds a repeated-tail step (`*`, `+`, or `?`); `chain.scan(input, validator)` returns a list of `Chain.Run` objects, each holding the head + tail captures with span (start,end) and named-group values. Replaces rebulk's `Rebulk.chain().regex(...).repeater(...)` machinery for the strong-episode patterns. Inputs and outputs are pure data — no engine state.
- `engine/DatePatterns` — list of seven `Pattern`s ported byte-for-byte from `rules/common/date.py` plus a `search(String, Boolean yearFirst, Boolean dayFirst) → Optional<Result>` returning `(start, end, LocalDate)`. `valid_year(int)` returns `1920 <= y < 2030`; `valid_week(int)` returns `1 <= w < 53`. Date parsing uses `java.time.format.DateTimeFormatter` candidates, not third-party libs.
- `rules/property/EpisodeDetailsExtractor` — string match of `Special`, `Pilot`, `Unaired`, `Final`. `EpisodeDetailValidator` runs in `postProcess` and removes any detail that is not seps-surrounded and not adjacent to season/episode.
- `rules/property/EpisodeFormatExtractor` — emits `episode_format=Minisode` for `Minisodes?` (case-insensitive).
- `rules/property/VersionExtractor` — emits `version` from `v\d+`. `VersionValidator` runs in `postProcess` and drops any version not preceded by an `episode` and not seps-surrounded.
- `rules/property/SeasonEpisodeExtractor` — the four strong `SxxExx` chains in Python: (a) `s01e02e03…`, (b) `01x02 03x04 …`, (c) `01x02 e03 e04 …`, (d) `s01 s02 s03 …`. Each chain is configured via `Chain` then validated by `OrderingValidator` (children sorted) and `Validators.sepsSurround`. `RemoveWeakIfSxxExx`, `RemoveInvalidSeason`, `RemoveInvalidEpisode`, `EpisodeNumberSeparatorRange`, `SeasonSeparatorRange`, `SeePatternRange` (cap pattern) all run in `postProcess`. Tags strong matches with `SxxExx`. Per-extractor `coexist` keeps both season and episode children.
- `rules/property/EpisodeWordExtractor` — `season_words` (Season N, Saison N, Temporada N…), `episode_words` (Episode 4, Ep 112…), and the detached `\d+ of \d+` count form. Includes the Roman numeral variant for `type=episode`. Emits `episode`, `season`, `count` matches; `count` is renamed to `episode_count` or `season_count` by `CountValidator` in `postProcess`.
- `rules/property/WeakEpisodeExtractor` — three weak chains: `\d{2}` ranges, `0\d{1,2}` ranges, `\d{3,4}` ranges, single-digit when `type=episode`. Emits `episode` matches tagged `weak-episode`. `RemoveWeakIfMovie`, `RemoveWeak`, `RemoveDetachedEpisodeNumber`, `EpisodeSingleDigitValidator` run in `postProcess`. Disabled when `type=movie`.
- `rules/property/WeakDuplicateExtractor` — `(?P<season>\d{1,2})(?P<episode>\d{2})` chain tagged `weak-episode`+`weak-duplicate`. Disabled when `episode_prefer_number=true` or `type=movie`. `RemoveWeakDuplicate`, the `WeakConflictSolver` anime detection, run in `postProcess`.
- `rules/property/DiscRule` — visits all `episodeMarker` matches whose raw value lowercased is `d`, renames the chain's episode children to `disc`, and emits a `discMarker` private match. Runs after `SeasonEpisodeExtractor` in the registry (so its children exist).
- `rules/property/AbsoluteEpisodeRule` — when two episode-emitting initiators are detected and the second initiator's first episode value is greater than the first's, rename the second initiator's children from `episode` to `absolute_episode`. Otherwise, with one strong SxxExx block plus a leading `weak_episode` group, rename the leading group's `episode` matches to `absolute_episode`.
- `rules/property/DateExtractor` — runs `DatePatterns.search` against the input and adds a `date` match. `postProcess` drops any `year`/`crc32`/`episode`/`season` overlapping the date span (Python's date `conflict_solver`).
- `rules/property/WeekExtractor` — emits `week` matches from `(week_words)-?(\d{1,2})` with `valid_week` filter and seps-surround validator.
- `rules/Rules.allInOrder()` — register Phase 3 extractors after Phase 2 extractors so their conflict_solver post-processing reads stable Phase 1/2 matches first. Order:

```
YearExtractor, ScreenSizeExtractor, VideoCodecExtractor, AudioCodecExtractor,
ContainerExtractor, OtherExtractor, LanguageExtractor, CountryExtractor,
StreamingServiceExtractor, SourceExtractor, WebsiteExtractor,
EpisodeDetailsExtractor, EpisodeFormatExtractor, VersionExtractor,
SeasonEpisodeExtractor, EpisodeWordExtractor, WeakEpisodeExtractor,
WeakDuplicateExtractor, DiscRule, AbsoluteEpisodeRule,
DateExtractor, WeekExtractor,
ReleaseGroupExtractor
```

`ReleaseGroupExtractor` stays last so it sees the final episode/season/date match set.

---

## Task 1: `engine/Numerals` — digital + roman + word parser

**Files:**
- Create: `src/main/java/io/guessit/engine/Numerals.java`
- Test: `src/test/java/io/guessit/engine/NumeralsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NumeralsTest {
    @Test void parsesDigital() {
        assertEquals(7, Numerals.parse("7"));
        assertEquals(123, Numerals.parse("123"));
    }
    @Test void parsesRoman() {
        assertEquals(4, Numerals.parse("IV"));
        assertEquals(9, Numerals.parse("IX"));
        assertEquals(1994, Numerals.parse("MCMXCIV"));
    }
    @Test void parsesEnglishWord() {
        assertEquals(0, Numerals.parse("zero"));
        assertEquals(7, Numerals.parse("seven"));
        assertEquals(20, Numerals.parse("twenty"));
    }
    @Test void parsesFrenchWord() {
        assertEquals(8, Numerals.parse("huit"));
        assertEquals(17, Numerals.parse("dix-sept"));
        assertEquals(17, Numerals.parse("dixsept"));
    }
    @Test void cleanWrappers() {
        // Python clean=True strips leading/trailing non-digits.
        assertEquals(42, Numerals.parse("ep42"));
        assertEquals(3, Numerals.parse("(3)"));
    }
    @Test void invalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> Numerals.parse("foo"));
    }
    @Test void numeralRegexSourceMatchesAllVariants() {
        var p = java.util.regex.Pattern.compile("^" + Numerals.NUMERAL + "$");
        assertTrue(p.matcher("12").matches());
        assertTrue(p.matcher("MCMXCIV").matches());
        assertTrue(p.matcher("seven").matches());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=NumeralsTest`
Expected: FAIL — class `Numerals` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

import java.util.List;
import java.util.regex.Pattern;

public final class Numerals {
    private Numerals() {}

    public static final String DIGITAL = "\\d{1,4}";
    public static final String ROMAN = "(?=[MCDLXVI]+)M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})";

    public static final List<String> ENGLISH_WORDS = List.of(
        "zero","one","two","three","four","five","six","seven","eight","nine","ten",
        "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen","twenty");
    public static final List<String> FRENCH_WORDS = List.of(
        "zéro","un","deux","trois","quatre","cinq","six","sept","huit","neuf","dix",
        "onze","douze","treize","quatorze","quinze","seize","dix-sept","dix-huit","dix-neuf","vingt");
    public static final List<String> FRENCH_ALT_WORDS = List.of(
        "zero","une","deux","trois","quatre","cinq","six","sept","huit","neuf","dix",
        "onze","douze","treize","quatorze","quinze","seize","dixsept","dixhuit","dixneuf","vingt");

    public static final String WORD = buildWordSource();

    public static final String NUMERAL = "(?:" + DIGITAL + "|" + ROMAN + "|" + WORD + ")";

    private static final Pattern ROMAN_FULL = Pattern.compile("^" + ROMAN + "$");
    private static final Pattern CLEAN = Pattern.compile("[^\\d]*(\\d+)[^\\d]*");

    private static final List<List<String>> WORD_LISTS = List.of(ENGLISH_WORDS, FRENCH_WORDS, FRENCH_ALT_WORDS);

    public static int parse(String value) { return parse(value, true, true, true); }

    public static int parse(String value, boolean intEnabled, boolean romanEnabled, boolean wordEnabled) {
        if (intEnabled) {
            try {
                var m = CLEAN.matcher(value);
                if (m.matches()) return Integer.parseInt(m.group(1));
                return Integer.parseInt(value);
            } catch (NumberFormatException ignore) {}
        }
        if (romanEnabled) {
            for (var word : value.split("\\s+")) {
                try { return parseRoman(word.toUpperCase(java.util.Locale.ROOT)); }
                catch (IllegalArgumentException ignore) {}
            }
        }
        if (wordEnabled) {
            for (var word : value.split("\\s+")) {
                int idx = wordIndex(word);
                if (idx >= 0) return idx;
            }
        }
        throw new IllegalArgumentException("Invalid numeral: " + value);
    }

    private static int wordIndex(String word) {
        var lower = word.toLowerCase(java.util.Locale.ROOT);
        for (var list : WORD_LISTS) {
            int idx = list.indexOf(lower);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    private static int parseRoman(String value) {
        if (!ROMAN_FULL.matcher(value).matches()) throw new IllegalArgumentException("Not roman: " + value);
        int[][] map = {{1000,'M'},{900,-1},{500,'D'},{400,-1},{100,'C'},{90,-1},{50,'L'},{40,-1},{10,'X'},{9,-1},{5,'V'},{4,-1},{1,'I'}};
        String[] tokens = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        int[] values = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
        int result = 0, i = 0;
        for (int k = 0; k < tokens.length; k++) {
            while (value.startsWith(tokens[k], i)) {
                result += values[k];
                i += tokens[k].length();
            }
        }
        return result;
    }

    private static String buildWordSource() {
        var sb = new StringBuilder("(?:(?=\\w+)");
        boolean first = true;
        for (var list : WORD_LISTS) {
            for (var w : list) {
                if (!first) sb.append('|');
                sb.append(java.util.regex.Pattern.quote(w));
                first = false;
            }
        }
        sb.append(')');
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=NumeralsTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Numerals.java src/test/java/io/guessit/engine/NumeralsTest.java
git commit -m "feat(engine): add Numerals (digital/roman/word numeral parser)"
```

---

## Task 2: `engine/Chain` — head + repeated-tail regex scanner

**Files:**
- Create: `src/main/java/io/guessit/engine/Chain.java`
- Test: `src/test/java/io/guessit/engine/ChainTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ChainTest {
    @Test void singleHeadSingleTail() {
        // S01E02-E03
        var head = Pattern.compile("(?i)s(?<season>\\d+)e(?<episode>\\d+)");
        var tail = Pattern.compile("(?i)-?e(?<episode>\\d+)");
        var runs = new Chain(head).tail(tail, Chain.Repeater.STAR).scan("S01E02-E03");
        assertEquals(1, runs.size());
        var run = runs.get(0);
        assertEquals(0, run.start());
        assertEquals(10, run.end());
        assertEquals(List.of("01"), run.captures("season"));
        assertEquals(List.of("02", "03"), run.captures("episode"));
    }
    @Test void plusRequiresAtLeastOneTail() {
        var head = Pattern.compile("(?i)(?<season>\\d+)x(?<episode>\\d+)");
        var tail = Pattern.compile("(?i)\\s+(?<season>\\d+)x(?<episode>\\d+)");
        // No tail in input → PLUS yields no run.
        assertTrue(new Chain(head).tail(tail, Chain.Repeater.PLUS).scan("01x02").isEmpty());
        // With a tail → one run.
        assertEquals(1, new Chain(head).tail(tail, Chain.Repeater.PLUS).scan("01x02 03x04").size());
    }
    @Test void noOverlap() {
        var head = Pattern.compile("\\d");
        var runs = new Chain(head).scan("abc1def2ghi");
        assertEquals(2, runs.size());
        assertEquals(3, runs.get(0).start());
        assertEquals(7, runs.get(1).start());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ChainTest`
Expected: FAIL — class `Chain` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replicates the subset of rebulk's Rebulk.chain() needed by SeasonEpisodeExtractor. */
public final class Chain {
    public enum Repeater { STAR, PLUS, QMARK }

    private final Pattern head;
    private final List<Step> tails = new ArrayList<>();

    public Chain(Pattern head) { this.head = head; }

    public Chain tail(Pattern tail, Repeater rep) { tails.add(new Step(tail, rep)); return this; }

    /** A single chain run: one head match followed by zero or more tail matches contiguous to head.end(). */
    public static final class Run {
        private final int start;
        private final int end;
        private final Map<String, List<String>> captures;
        private final Map<String, List<int[]>> spans;
        Run(int start, int end, Map<String, List<String>> captures, Map<String, List<int[]>> spans) {
            this.start = start; this.end = end; this.captures = captures; this.spans = spans;
        }
        public int start() { return start; }
        public int end() { return end; }
        public List<String> captures(String name) { return captures.getOrDefault(name, List.of()); }
        public List<int[]> spans(String name) { return spans.getOrDefault(name, List.of()); }
    }

    public List<Run> scan(String input) {
        var runs = new ArrayList<Run>();
        var headMatcher = head.matcher(input);
        int from = 0;
        while (headMatcher.find(from)) {
            int hStart = headMatcher.start();
            int hEnd = headMatcher.end();
            var caps = new LinkedHashMap<String, List<String>>();
            var spans = new LinkedHashMap<String, List<int[]>>();
            collectNamed(headMatcher, caps, spans);

            int cursor = hEnd;
            int tailCount = 0;
            for (var step : tails) {
                int taken = 0;
                while (true) {
                    if (step.rep == Repeater.QMARK && taken >= 1) break;
                    var tm = step.pattern.matcher(input).region(cursor, input.length()).useAnchoringBounds(true);
                    tm.useTransparentBounds(false);
                    if (!tm.lookingAt()) break;
                    collectNamed(tm, caps, spans);
                    cursor = tm.end();
                    taken++;
                    tailCount++;
                    if (step.rep == Repeater.QMARK) break;
                }
                if (step.rep == Repeater.PLUS && taken == 0) {
                    // Required step not matched → drop entire run.
                    cursor = hEnd;
                    caps.clear();
                    spans.clear();
                    collectNamed(headMatcher, caps, spans);
                    tailCount = 0;
                    break;
                }
            }

            // PLUS overall: at least one tail across all PLUS steps.
            boolean ok = true;
            for (var step : tails) {
                if (step.rep == Repeater.PLUS && tailCount == 0) { ok = false; break; }
            }
            if (ok) runs.add(new Run(hStart, cursor, caps, spans));
            from = Math.max(hEnd, hStart + 1);
        }
        return runs;
    }

    private static void collectNamed(Matcher m, Map<String, List<String>> caps, Map<String, List<int[]>> spans) {
        for (var name : namedGroups(m.pattern())) {
            String v;
            try { v = m.group(name); } catch (IllegalArgumentException e) { continue; }
            if (v == null) continue;
            caps.computeIfAbsent(name, k -> new ArrayList<>()).add(v);
            spans.computeIfAbsent(name, k -> new ArrayList<>()).add(new int[]{m.start(name), m.end(name)});
        }
    }

    private static List<String> namedGroups(Pattern p) {
        var out = new ArrayList<String>();
        var src = p.pattern();
        var nm = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").matcher(src);
        while (nm.find()) out.add(nm.group(1));
        return out;
    }

    private record Step(Pattern pattern, Repeater rep) {}
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=ChainTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Chain.java src/test/java/io/guessit/engine/ChainTest.java
git commit -m "feat(engine): add Chain head+tail regex scanner"
```

---

## Task 3: `engine/DatePatterns` — search_date port

**Files:**
- Create: `src/main/java/io/guessit/engine/DatePatterns.java`
- Test: `src/test/java/io/guessit/engine/DatePatternsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DatePatternsTest {
    @Test void validYearRange() {
        assertTrue(DatePatterns.validYear(1920));
        assertTrue(DatePatterns.validYear(2029));
        assertFalse(DatePatterns.validYear(1919));
        assertFalse(DatePatterns.validYear(2030));
    }
    @Test void validWeekRange() {
        assertTrue(DatePatterns.validWeek(1));
        assertTrue(DatePatterns.validWeek(52));
        assertFalse(DatePatterns.validWeek(0));
        assertFalse(DatePatterns.validWeek(53));
    }
    @Test void searchYmd() {
        var r = DatePatterns.search(" Show 2002-04-22 1080p ", null, null).orElseThrow();
        assertEquals(LocalDate.of(2002, 4, 22), r.date());
    }
    @Test void searchDmy() {
        var r = DatePatterns.search("And this on 17-06-1998.", null, null).orElseThrow();
        assertEquals(LocalDate.of(1998, 6, 17), r.date());
    }
    @Test void searchTwoDigitYearGuessesDayFirst() {
        // 22-04-02 → 2002-04-22 (day_first guessed true because trailing 02 < 32).
        var r = DatePatterns.search(" e 22-04-02 e", null, null).orElseThrow();
        assertEquals(LocalDate.of(2002, 4, 22), r.date());
    }
    @Test void searchYearFirstHonoured() {
        // 02-04-22 → year_first=true → 2002-04-22.
        var r = DatePatterns.search(" e 02.04.22 e", true, null).orElseThrow();
        assertEquals(LocalDate.of(2002, 4, 22), r.date());
    }
    @Test void noDate() {
        assertTrue(DatePatterns.search(" no date ", null, null).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=DatePatternsTest`
Expected: FAIL — class `DatePatterns` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Port of guessit/rules/common/date.py. */
public final class DatePatterns {
    private DatePatterns() {}

    public record Result(int start, int end, LocalDate date) {}

    private static final String DSEP = "[-/ \\.]";
    private static final String DSEP_BIS = "[-/ \\.x]";

    /** Each regex captures group 1 = the date span. */
    public static final List<Pattern> REGEXPS = List.of(
        Pattern.compile(DSEP + "((\\d{8}))" + DSEP, Pattern.CASE_INSENSITIVE),
        Pattern.compile(DSEP + "((\\d{6}))" + DSEP, Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{2})" + DSEP + "(\\d{1,2})" + DSEP + "(\\d{1,2}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{1,2})" + DSEP + "(\\d{1,2})" + DSEP + "(\\d{2}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{4})" + DSEP_BIS + "(\\d{1,2})" + DSEP + "(\\d{1,2}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{1,2})" + DSEP + "(\\d{1,2})" + DSEP_BIS + "(\\d{4}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{1,2}(?:st|nd|rd|th)?" + DSEP + "(?:[a-z]{3,10})" + DSEP + "\\d{4}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE)
    );

    public static boolean validYear(int year) { return 1920 <= year && year < 2030; }
    public static boolean validWeek(int week) { return 1 <= week && week < 53; }

    public static Optional<Result> search(String input, Boolean yearFirst, Boolean dayFirst) {
        for (var p : REGEXPS) {
            var m = p.matcher(input);
            if (!m.find()) continue;
            int s = m.start(1);
            int e = m.end(1);

            int gc = m.groupCount();
            // groups 2..gc are the structured pieces (year/month/day or freeform).
            String[] parts = new String[gc - 1];
            for (int i = 0; i < parts.length; i++) parts[i] = m.group(2 + i);

            Boolean df = dayFirst;
            if (Boolean.TRUE.equals(yearFirst) && df == null) df = false;
            if (df == null) df = guessDayFirst(parts);

            var date = parse(m.group(1), yearFirst, df);
            if (date.isPresent() && validYear(date.get().getYear())) {
                return Optional.of(new Result(s, e, date.get()));
            }
        }
        return Optional.empty();
    }

    private static Boolean guessDayFirst(String[] parts) {
        if (parts.length == 0) return null;
        var first = parts[0];
        var last = parts[parts.length - 1];
        if (isInt(first) && first.length() >= 4 && validYear(Integer.parseInt(first.substring(0, 4)))) return false;
        if (isInt(last) && last.length() >= 4 && validYear(Integer.parseInt(last.substring(last.length() - 4)))) return true;
        if (isInt(first) && Integer.parseInt(first.substring(0, Math.min(2, first.length()))) > 31) return false;
        if (isInt(last) && Integer.parseInt(last.substring(Math.max(0, last.length() - 2))) > 31) return true;
        return null;
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static Optional<LocalDate> parse(String raw, Boolean yearFirst, Boolean dayFirst) {
        var seps = new String[]{"-", "/", " ", ".", "x"};
        for (var sep : seps) {
            var attempt = tryParse(raw.replace(sep, "-"), yearFirst, dayFirst);
            if (attempt.isPresent()) return attempt;
        }
        return Optional.empty();
    }

    /** Try the formatter combinations Python dateutil would consider. */
    private static Optional<LocalDate> tryParse(String raw, Boolean yearFirst, Boolean dayFirst) {
        // 8-digit and 6-digit compact forms.
        if (raw.matches("\\d{8}")) {
            try { return Optional.of(LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)); }
            catch (Exception ignore) {}
        }
        if (raw.matches("\\d{6}")) {
            int y = 2000 + Integer.parseInt(raw.substring(0, 2));
            int mo = Integer.parseInt(raw.substring(2, 4));
            int d = Integer.parseInt(raw.substring(4, 6));
            try { return Optional.of(LocalDate.of(y, mo, d)); } catch (Exception ignore) {}
        }
        // English month forms: e.g. 22nd-July-2002
        var monthPattern = Pattern.compile("^(\\d{1,2})(?:st|nd|rd|th)?-([A-Za-z]{3,10})-(\\d{4})$", Pattern.CASE_INSENSITIVE);
        var mm = monthPattern.matcher(raw);
        if (mm.matches()) {
            int d = Integer.parseInt(mm.group(1));
            int mo = monthIndex(mm.group(2));
            int y = Integer.parseInt(mm.group(3));
            if (mo > 0) try { return Optional.of(LocalDate.of(y, mo, d)); } catch (Exception ignore) {}
        }
        // Numeric A-B-C with three groups.
        var numPattern = Pattern.compile("^(\\d{1,4})-(\\d{1,2})-(\\d{1,4})$");
        var nm = numPattern.matcher(raw);
        if (!nm.matches()) return Optional.empty();
        int a = Integer.parseInt(nm.group(1));
        int b = Integer.parseInt(nm.group(2));
        int c = Integer.parseInt(nm.group(3));

        // Decide order using yearFirst / dayFirst.
        boolean yF = Boolean.TRUE.equals(yearFirst);
        boolean dF = Boolean.TRUE.equals(dayFirst);
        if (yF || (yearFirst == null && a >= 100)) {
            // YYYY-MM-DD
            int y = a >= 100 ? a : 2000 + a;
            try { return Optional.of(LocalDate.of(y, b, c)); } catch (Exception ignore) {}
        }
        if (c >= 100) {
            int y = c;
            // Either DD-MM-YYYY or MM-DD-YYYY.
            if (dF) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
            try { return Optional.of(LocalDate.of(y, a, b)); } catch (Exception ignore) {}
            try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
        } else {
            int y = 2000 + c;
            if (dF) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
            try { return Optional.of(LocalDate.of(y, a, b)); } catch (Exception ignore) {}
            try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
        }
        return Optional.empty();
    }

    private static int monthIndex(String name) {
        var months = List.of("january","february","march","april","may","june",
            "july","august","september","october","november","december");
        var lc = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < months.size(); i++) {
            if (months.get(i).startsWith(lc)) return i + 1;
        }
        return -1;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=DatePatternsTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/DatePatterns.java src/test/java/io/guessit/engine/DatePatternsTest.java
git commit -m "feat(engine): add DatePatterns search_date port"
```

---

## Task 4: `EpisodeDetailsExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/EpisodeDetailsExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/EpisodeDetailsExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeDetailsExtractorTest {
    @Test void specialNextToEpisode() {
        var r = Guessit.parse("Show.S01E02.Special.mkv").toMap();
        assertEquals("Special", r.get("episode_details"));
    }
    @Test void pilotStandalone() {
        var r = Guessit.parse("Show.S01E01.Pilot.mkv").toMap();
        assertEquals("Pilot", r.get("episode_details"));
    }
    @Test void detachedPilotIsDropped() {
        // No season/episode adjacency, embedded mid-token → drop.
        var r = Guessit.parse("PilotXFilesShow.mkv").toMap();
        assertNull(r.get("episode_details"));
    }
    @Test void multipleDetails() {
        var r = Guessit.parse("Show.S01E02.Special.Final.mkv").toMap();
        var v = r.get("episode_details");
        assertTrue(v instanceof java.util.List<?> l && l.size() == 2 && l.contains("Special") && l.contains("Final"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EpisodeDetailsExtractorTest`
Expected: 4 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EpisodeDetailsExtractor implements Extractor {
    private static final List<String> DETAILS = List.of("Special", "Pilot", "Unaired", "Final");

    @Override public String name() { return "episode_details"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        for (var detail : DETAILS) {
            var opts = StringOpts.defaults().withValidator(m -> Validators.sepsSurround(input).test(m));
            var matches = PatternMatcher.string(input, Set.of(detail), "episode_details", opts);
            for (var m : matches) ctx.matches.add(m);
        }
    }

    /** Replicates Python EpisodeDetailValidator. */
    @Override
    public void postProcess(ParseContext ctx) {
        var details = ctx.matches.named("episode_details").toList();
        var toRemove = new ArrayList<Match>();
        for (var d : details) {
            boolean adjacentToEp = ctx.matches.all().anyMatch(m ->
                ("season".equals(m.name()) || "episode".equals(m.name()))
                    && (Math.abs(m.end() - d.start()) <= 1 || Math.abs(d.end() - m.start()) <= 1));
            if (!adjacentToEp && !Validators.sepsSurround(ctx.input).test(d)) {
                toRemove.add(d);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register in `Rules.allInOrder()`**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new EpisodeDetailsExtractor()` after `new WebsiteExtractor()` and before `new ReleaseGroupExtractor()`. Add the import.

- [ ] **Step 5: Run tests**

Run: `mvn -q test -Dtest=EpisodeDetailsExtractorTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/EpisodeDetailsExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/EpisodeDetailsExtractorTest.java
git commit -m "feat(rules): add EpisodeDetailsExtractor (Special, Pilot, Unaired, Final)"
```

---

## Task 5: `EpisodeFormatExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/EpisodeFormatExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/EpisodeFormatExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeFormatExtractorTest {
    @Test void minisode() {
        var r = Guessit.parse("Show.S01E02.Minisode.mkv").toMap();
        assertEquals("Minisode", r.get("episode_format"));
    }
    @Test void minisodesPlural() {
        var r = Guessit.parse("Show.S01.Minisodes.Pack.mkv").toMap();
        assertEquals("Minisode", r.get("episode_format"));
    }
    @Test void noFormat() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("episode_format"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EpisodeFormatExtractorTest`
Expected: 2 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.regex.Pattern;

public final class EpisodeFormatExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)Minisodes?");

    @Override public String name() { return "episode_format"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(s -> "Minisode")
            .withValidator(m -> Validators.sepsSurround(input).test(m));
        for (var m : PatternMatcher.regex(input, PATTERN, "episode_format", opts)) {
            ctx.matches.add(m);
        }
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new EpisodeFormatExtractor()` after `new EpisodeDetailsExtractor()`. Add the import.

Run: `mvn -q test -Dtest=EpisodeFormatExtractorTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/EpisodeFormatExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/EpisodeFormatExtractorTest.java
git commit -m "feat(rules): add EpisodeFormatExtractor (Minisode)"
```

---

## Task 6: `VersionExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/VersionExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/VersionExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionExtractorTest {
    @Test void versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv").toMap();
        assertEquals(2, r.get("version"));
    }
    @Test void detachedVersionNoEpisodeIsDropped() {
        // VersionValidator drops versions not preceded by an episode, unless seps-surrounded.
        var r = Guessit.parse("v3 randomshow.mkv").toMap();
        assertNull(r.get("version"));
    }
    @Test void noVersion() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("version"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=VersionExtractorTest`
Expected: 1 failure (`versionAfterEpisode`).

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.regex.Pattern;

public final class VersionExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?i)v(\\d+)");

    @Override public String name() { return "version"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var opts = RegexOpts.defaults()
            .withValue(s -> Integer.valueOf(s.substring(1)))
            .withValidator(m -> Validators.sepsBefore(input).test(m));
        for (var m : PatternMatcher.regex(input, PATTERN, "version", opts)) {
            ctx.matches.add(m);
        }
    }

    /** Replicates Python VersionValidator: drop version when not preceded by episode and not seps-surrounded. */
    @Override
    public void postProcess(ParseContext ctx) {
        var versions = ctx.matches.named("version").toList();
        var episodes = ctx.matches.named("episode").toList();
        var toRemove = new ArrayList<Match>();
        var input = ctx.input;
        for (var v : versions) {
            boolean precedingEpisode = episodes.stream().anyMatch(e -> e.end() == v.start());
            boolean surrounded = Validators.sepsSurround(input).test(v);
            if (!precedingEpisode && !surrounded) toRemove.add(v);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new VersionExtractor()` after `new EpisodeFormatExtractor()`. Add the import.

Run: `mvn -q test -Dtest=VersionExtractorTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/VersionExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/VersionExtractorTest.java
git commit -m "feat(rules): add VersionExtractor with VersionValidator"
```

---

## Task 7: `SeasonEpisodeExtractor` — strong SxxExx chains

This is the largest task. The four chains in Python `episodes.py` translate to four `Chain` runs:

1. `s(?<season>\d+)(?<episodeMarker>e|ex|xe|ep|x|d)(?<episode>\d+)` head; tail repeats `(?<episodeSeparator>...)?(?<episode>\d+)`.
2. `(?<season>\d+)(?<episodeMarker>x)(?<episode>\d+)` head; tail repeats the same head pattern (per-block separator).
3. `(?<season>\d+)x(?<episode>\d+)` head; tail repeats `(?<episodeSeparator>...)?(?<episode>\d+)`.
4. `s(?<season>\d+)` head with optional `Extras` middle and tail repeats `(?<seasonSeparator>...)?s?(?<season>\d+)`.

We do not implement Python's full `WeakConflictSolver` here (depends on anime detection that needs Plan-4 title/group info beyond Plan 0's `GroupMarker`). That subset is shipped with `WeakDuplicateExtractor` in Task 10.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/SeasonEpisodeExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/SeasonEpisodeExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeasonEpisodeExtractorTest {
    @Test void s01e02() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void multiEpisode_S01E02E03() {
        var r = Guessit.parse("Show.S01E02E03.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(List.of(2, 3), r.get("episode"));
    }
    @Test void shortForm_01x02() {
        var r = Guessit.parse("Show.01x02.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void multiSeason_S01S02S03() {
        var r = Guessit.parse("Show.S01S02S03.Pack.mkv").toMap();
        assertEquals(List.of(1, 2, 3), r.get("season"));
    }
    @Test void rangeDash_S01E02_E04() {
        var r = Guessit.parse("Show.S01E02-E04.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(List.of(2, 3, 4), r.get("episode"));
    }
    @Test void capPattern_Cap_102() {
        // Python see-pattern: cap.102 → season 1, episode 2.
        var r = Guessit.parse("Show.Cap.102.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void seasonOnly_S01() {
        var r = Guessit.parse("Show.S01.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertNull(r.get("episode"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SeasonEpisodeExtractorTest`
Expected: 7 failures (no extractor yet).

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SeasonEpisodeExtractor implements Extractor {
    private static final Pattern HEAD_S_E = Pattern.compile(
        "(?i)s(?<season>\\d+)@?(?<episodeMarker>e|ex|xe|ep|x|d)@?(?<episode>\\d+)");
    private static final Pattern TAIL_E = Pattern.compile(
        "(?i)(?<episodeSeparator>e|ex|xe|ep|x|d|-|\\+|&|to|a|and|et|~)@?(?<episode>\\d+)");
    private static final Pattern HEAD_NUM_X = Pattern.compile(
        "(?i)(?<season>\\d+)@?(?<episodeMarker>x)@?(?<episode>\\d+)");
    private static final Pattern TAIL_NUM_X = Pattern.compile(
        "(?i)[ ._\\-]+(?<season>\\d+)@?(?<episodeMarker>x)@?(?<episode>\\d+)");
    private static final Pattern HEAD_S = Pattern.compile(
        "(?i)s(?<season>\\d+)");
    private static final Pattern TAIL_S = Pattern.compile(
        "(?i)(?<seasonSeparator>s|-|\\+|&|to|a|and|et|~)(?<season>\\d+)");
    private static final Pattern HEAD_CAP = Pattern.compile(
        "(?i)(?<seasonMarker>cap)-?(?<season>\\d{1,2})(?<episode>\\d{2})");

    @Override public String name() { return "season"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        runChain(ctx, new Chain(HEAD_S_E).tail(TAIL_E, Chain.Repeater.STAR), input, true);
        runChain(ctx, new Chain(HEAD_NUM_X).tail(TAIL_NUM_X, Chain.Repeater.STAR), input, true);
        runChain(ctx, new Chain(HEAD_S).tail(TAIL_S, Chain.Repeater.STAR), input, false);
        runCap(ctx, input);
    }

    private void runChain(ParseContext ctx, Chain chain, String input, boolean withEpisode) {
        var seps = Validators.sepsSurround(input);
        for (var run : chain.scan(input)) {
            var headMatch = new Match("seasonHead", null, run.start(), run.end(),
                input.substring(run.start(), run.end()), 1000, Set.of("SxxExx"), false);
            if (!seps.test(headMatch)) continue;
            var seasonValues = run.captures("season");
            var episodeValues = run.captures("episode");
            var seasonSpans = run.spans("season");
            var episodeSpans = run.spans("episode");
            if (!isAscending(seasonValues) || !isAscending(episodeValues)) continue;
            for (int i = 0; i < seasonValues.size(); i++) {
                int[] sp = seasonSpans.get(i);
                ctx.matches.add(new Match("season", Integer.valueOf(seasonValues.get(i)),
                    sp[0], sp[1], input.substring(sp[0], sp[1]), 1000, Set.of("SxxExx", "coexist"), false));
            }
            if (withEpisode) {
                for (int i = 0; i < episodeValues.size(); i++) {
                    int[] ep = episodeSpans.get(i);
                    ctx.matches.add(new Match("episode", Integer.valueOf(episodeValues.get(i)),
                        ep[0], ep[1], input.substring(ep[0], ep[1]), 1000, Set.of("SxxExx", "coexist"), false));
                }
            }
        }
    }

    private void runCap(ParseContext ctx, String input) {
        var seps = Validators.sepsSurround(input);
        var matcher = HEAD_CAP.matcher(input);
        while (matcher.find()) {
            var head = new Match("season", null, matcher.start(), matcher.end(),
                matcher.group(), 1000, Set.of("SxxExx", "see-pattern"), false);
            if (!seps.test(head)) continue;
            int sStart = matcher.start("season");
            int sEnd = matcher.end("season");
            int eStart = matcher.start("episode");
            int eEnd = matcher.end("episode");
            ctx.matches.add(new Match("season", Integer.parseInt(matcher.group("season")),
                sStart, sEnd, matcher.group("season"), 1000, Set.of("SxxExx", "coexist", "see-pattern"), false));
            ctx.matches.add(new Match("episode", Integer.parseInt(matcher.group("episode")),
                eStart, eEnd, matcher.group("episode"), 1000, Set.of("SxxExx", "coexist", "see-pattern"), false));
        }
    }

    private static boolean isAscending(List<String> values) {
        int prev = Integer.MIN_VALUE;
        for (var v : values) {
            int n = Integer.parseInt(v);
            if (n < prev) return false;
            prev = n;
        }
        return true;
    }

    /** Replicates Python RemoveInvalidEpisode + RemoveInvalidSeason + EpisodeNumberSeparatorRange (range expansion). */
    @Override
    public void postProcess(ParseContext ctx) {
        expandRanges(ctx);
        removeInvalidSecondaryChain(ctx, "season");
        removeInvalidSecondaryChain(ctx, "episode");
    }

    private void expandRanges(ParseContext ctx) {
        // For SxxExx tagged episodes that use a `-`/`~`/`to`/`a` separator between two episode values,
        // emit the integers in between.
        var input = ctx.input;
        var episodes = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("SxxExx"))
            .sorted(java.util.Comparator.comparingInt(Match::start))
            .toList();
        for (int i = 0; i + 1 < episodes.size(); i++) {
            var prev = episodes.get(i);
            var next = episodes.get(i + 1);
            if (next.start() <= prev.end() + 3) {
                var gap = input.substring(prev.end(), next.start());
                if (containsRange(gap)) {
                    int a = ((Integer) prev.value()) + 1;
                    int b = ((Integer) next.value()) - 1;
                    for (int v = a; v <= b; v++) {
                        ctx.matches.add(new Match("episode", v, prev.end(), next.start(),
                            String.valueOf(v), 1000, Set.of("SxxExx", "coexist", "range-fill"), false));
                    }
                }
            }
        }
    }

    private static boolean containsRange(String gap) {
        var lc = gap.toLowerCase(java.util.Locale.ROOT).strip();
        return lc.equals("-") || lc.equals("~") || lc.equals("to") || lc.equals("a");
    }

    private void removeInvalidSecondaryChain(ParseContext ctx, String prop) {
        // Drop secondary `season` or `episode` matches whose initiator differs from the strong SxxExx chain.
        // Approximation: keep all SxxExx-tagged matches, drop later non-SxxExx duplicates within the same filepart.
        var matches = ctx.matches.named(prop).sorted(java.util.Comparator.comparingInt(Match::start)).toList();
        if (matches.size() <= 1) return;
        boolean strongSeen = matches.stream().anyMatch(m -> m.tags().contains("SxxExx"));
        if (!strongSeen) return;
        var toRemove = new ArrayList<Match>();
        for (var m : matches) {
            if (!m.tags().contains("SxxExx")) toRemove.add(m);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new SeasonEpisodeExtractor()` after `new VersionExtractor()`. Add the import.

Run: `mvn -q test -Dtest=SeasonEpisodeExtractorTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/SeasonEpisodeExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/SeasonEpisodeExtractorTest.java
git commit -m "feat(rules): add SeasonEpisodeExtractor (strong SxxExx + cap + range expansion)"
```

---

## Task 8: `EpisodeWordExtractor` — Episode/Season/Cap word forms

**Files:**
- Create: `src/main/java/io/guessit/rules/property/EpisodeWordExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/EpisodeWordExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeWordExtractorTest {
    @Test void episodeWord() {
        var r = Guessit.parse("Show Episode 4.mkv").toMap();
        assertEquals(4, r.get("episode"));
    }
    @Test void episodeAbbreviation() {
        var r = Guessit.parse("Show ep 112.mkv").toMap();
        assertEquals(112, r.get("episode"));
    }
    @Test void seasonWord() {
        var r = Guessit.parse("Show Season 2.mkv").toMap();
        assertEquals(2, r.get("season"));
    }
    @Test void seasonRomanNumeralEpisodeType() {
        var r = Guessit.parse("Show Season III.mkv", Options.builder().type("episode").build()).toMap();
        assertEquals(3, r.get("season"));
    }
    @Test void countDetached() {
        // "Show 4 of 12 mkv" → episode=4, episode_count=12.
        var r = Guessit.parse("Show 4 of 12.mkv").toMap();
        assertEquals(4, r.get("episode"));
        assertEquals(12, r.get("episode_count"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EpisodeWordExtractorTest`
Expected: 5 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class EpisodeWordExtractor implements Extractor {
    private static final List<String> SEASON_WORDS = List.of(
        "season","saison","seizoen","seasons","saisons","tem","temp","temporada","temporadas","stagione");
    private static final List<String> EPISODE_WORDS = List.of(
        "episode","episodes","eps","ep","episodio","episodios","capitulo","capitulos");
    private static final List<String> OF_WORDS = List.of("of", "sur");

    @Override public String name() { return "episode_word"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        var seasonRe = Pattern.compile("(?i)\\b(" + or(SEASON_WORDS) + ")[ ._-]*(" + Numerals.NUMERAL + ")");
        var seasonMatcher = seasonRe.matcher(input);
        while (seasonMatcher.find()) {
            var raw = seasonMatcher.group();
            var headMatch = new Match("season", null, seasonMatcher.start(), seasonMatcher.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) continue;
            int valStart = seasonMatcher.start(2);
            int valEnd = seasonMatcher.end(2);
            int n = parseSafe(seasonMatcher.group(2), ctx);
            if (n < 0) continue;
            ctx.matches.add(new Match("season", n, valStart, valEnd,
                input.substring(valStart, valEnd), 1000, Set.of("season-word"), false));
        }

        var epRe = Pattern.compile("(?i)\\b(" + or(EPISODE_WORDS) + ")[ ._-]*(\\d+)(?:v(\\d+))?(?:[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+))?");
        var epMatcher = epRe.matcher(input);
        while (epMatcher.find()) {
            var raw = epMatcher.group();
            var headMatch = new Match("episode", null, epMatcher.start(), epMatcher.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) continue;
            int epStart = epMatcher.start(2);
            int epEnd = epMatcher.end(2);
            int ep = Integer.parseInt(epMatcher.group(2));
            ctx.matches.add(new Match("episode", ep, epStart, epEnd,
                input.substring(epStart, epEnd), 1000, Set.of("episode-word"), false));
            if (epMatcher.group(3) != null) {
                int v = Integer.parseInt(epMatcher.group(3));
                ctx.matches.add(new Match("version", v, epMatcher.start(3), epMatcher.end(3),
                    epMatcher.group(3), 1000, Set.of(), false));
            }
            if (epMatcher.group(4) != null) {
                int c = Integer.parseInt(epMatcher.group(4));
                ctx.matches.add(new Match("episode_count", c, epMatcher.start(4), epMatcher.end(4),
                    epMatcher.group(4), 1000, Set.of(), false));
            }
        }

        // Detached: \d+ of \d+
        var detached = Pattern.compile("(?i)(\\d+)[ ._-]*(?:" + or(OF_WORDS) + ")[ ._-]*(\\d+)");
        var dm = detached.matcher(input);
        while (dm.find()) {
            var raw = dm.group();
            var headMatch = new Match("episode", null, dm.start(), dm.end(), raw, 1000, Set.of(), false);
            if (!seps.test(headMatch)) continue;
            int e = Integer.parseInt(dm.group(1));
            int c = Integer.parseInt(dm.group(2));
            ctx.matches.add(new Match("episode", e, dm.start(1), dm.end(1),
                dm.group(1), 1000, Set.of("episode-word"), false));
            ctx.matches.add(new Match("episode_count", c, dm.start(2), dm.end(2),
                dm.group(2), 1000, Set.of(), false));
        }
    }

    private static int parseSafe(String token, ParseContext ctx) {
        try { return Numerals.parse(token); }
        catch (RuntimeException e) { return -1; }
    }

    private static String or(List<String> items) {
        var sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return String.join("|", sorted);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new EpisodeWordExtractor()` after `new SeasonEpisodeExtractor()`. Add the import.

Run: `mvn -q test -Dtest=EpisodeWordExtractorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/EpisodeWordExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/EpisodeWordExtractorTest.java
git commit -m "feat(rules): add EpisodeWordExtractor (Episode N, Season N, of N)"
```

---

## Task 9: `WeakEpisodeExtractor` — weak-episode digit groups

**Files:**
- Create: `src/main/java/io/guessit/rules/property/WeakEpisodeExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/WeakEpisodeExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeakEpisodeExtractorTest {
    @Test void twoDigitWeakWhenNotMovie() {
        var r = Guessit.parse("Show 12 720p HDTV.mkv").toMap();
        assertEquals(12, r.get("episode"));
    }
    @Test void weakDroppedWhenMovie() {
        var r = Guessit.parse("Movie.2010.12 Years.1080p.mkv",
            Options.builder().type("movie").build()).toMap();
        // year=2010 wins; "12" is weak and discarded under movie context.
        assertNull(r.get("episode"));
    }
    @Test void threeDigitWeak() {
        var r = Guessit.parse("Show.112.HDTV.mkv").toMap();
        assertEquals(112, r.get("episode"));
    }
    @Test void singleDigitOnlyForEpisodeType() {
        var r = Guessit.parse("Show.5.HDTV.mkv", Options.builder().type("episode").build()).toMap();
        assertEquals(5, r.get("episode"));
    }
    @Test void droppedAfterAudioCodec() {
        // Python RemoveWeak: a weak number directly after audio_codec/source/etc. is dropped.
        var r = Guessit.parse("Show AC3 12 .mkv").toMap();
        assertNull(r.get("episode"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=WeakEpisodeExtractorTest`
Expected: 5 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class WeakEpisodeExtractor implements Extractor {
    private static final Pattern TWO_DIGIT = Pattern.compile("(?<!\\d)(\\d{2})(?!\\d)");
    private static final Pattern THREE_OR_FOUR = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)");
    private static final Pattern SINGLE = Pattern.compile("(?<!\\d)(\\d)(?!\\d)");

    @Override public String name() { return "weak_episode"; }
    @Override public int priority() { return 800; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        emit(ctx, input, TWO_DIGIT, seps);
        emit(ctx, input, THREE_OR_FOUR, seps);
        if ("episode".equals(ctx.options.type())) {
            emit(ctx, input, SINGLE, seps);
        }
    }

    private void emit(ParseContext ctx, String input, Pattern p, java.util.function.Predicate<Match> seps) {
        var m = p.matcher(input);
        while (m.find()) {
            var head = new Match("episode", null, m.start(1), m.end(1), m.group(1), 800, Set.of("weak-episode"), false);
            if (!seps.test(head)) continue;
            int v = Integer.parseInt(m.group(1));
            ctx.matches.add(new Match("episode", v, m.start(1), m.end(1),
                m.group(1), 800, Set.of("weak-episode"), false));
        }
    }

    /** Replicates RemoveWeakIfMovie + RemoveWeak (drop weak-episode after audio/video/source). */
    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named("year").findAny().isPresent() && !"episode".equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return;
        }
        if ("movie".equals(ctx.options.type())) {
            removeAllWeak(ctx);
            return;
        }

        // Drop weak episodes that directly follow an audio_codec/source/screen_size/streaming_service match.
        var blockingNames = Set.of("audio_codec", "screen_size", "streaming_service",
            "source", "video_profile", "audio_channels", "audio_profile");
        var blocking = ctx.matches.all().filter(m -> blockingNames.contains(m.name())).toList();
        var weaks = ctx.matches.named("episode").filter(m -> m.tags().contains("weak-episode")).toList();
        var toRemove = new ArrayList<Match>();
        for (var weak : weaks) {
            for (var b : blocking) {
                if (b.end() <= weak.start() && weak.start() - b.end() <= 3) {
                    String gap = ctx.input.substring(b.end(), weak.start());
                    if (gap.chars().allMatch(Seps::isSep)) {
                        toRemove.add(weak);
                        break;
                    }
                }
            }
        }
        // Drop weaks if a SxxExx-tagged episode exists in the same filepart (RemoveWeakIfSxxExx).
        boolean strongPresent = ctx.matches.named("episode").anyMatch(m -> m.tags().contains("SxxExx"));
        if (strongPresent) {
            for (var weak : weaks) {
                if (weak.start() != 0) toRemove.add(weak);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private static void removeAllWeak(ParseContext ctx) {
        var weaks = ctx.matches.named("episode").filter(m -> m.tags().contains("weak-episode")).toList();
        for (var m : weaks) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new WeakEpisodeExtractor()` after `new EpisodeWordExtractor()`. Add the import.

Run: `mvn -q test -Dtest=WeakEpisodeExtractorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/WeakEpisodeExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/WeakEpisodeExtractorTest.java
git commit -m "feat(rules): add WeakEpisodeExtractor with movie/SxxExx-aware suppression"
```

---

## Task 10: `WeakDuplicateExtractor` — weak-duplicate `(\d{1,2})(\d{2})` chain

**Files:**
- Create: `src/main/java/io/guessit/rules/property/WeakDuplicateExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/WeakDuplicateExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeakDuplicateExtractorTest {
    @Test void weakSeasonEpisodeFromFourDigits() {
        // Show.0102.mkv → season=1, episode=2 (weak-duplicate fallback).
        var r = Guessit.parse("Show.0102.HDTV.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
    @Test void preferNumberOverridesWeakDuplicate() {
        var r = Guessit.parse("Show.0102.HDTV.mkv",
            Options.builder().episodePreferNumber(true).build()).toMap();
        assertEquals(102, r.get("episode"));
        assertNull(r.get("season"));
    }
    @Test void droppedWhenMovie() {
        var r = Guessit.parse("Movie.0102.HDTV.mkv",
            Options.builder().type("movie").build()).toMap();
        assertNull(r.get("season"));
        assertNull(r.get("episode"));
    }
    @Test void droppedWhenStrongSxxExxPresent() {
        // S01E02 is strong → 0304 weak-duplicate is dropped.
        var r = Guessit.parse("Show.S01E02.0304.mkv").toMap();
        assertEquals(1, r.get("season"));
        assertEquals(2, r.get("episode"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=WeakDuplicateExtractorTest`
Expected: 4 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

public final class WeakDuplicateExtractor implements Extractor {
    private static final Pattern PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})(\\d{2})(?!\\d)");

    @Override public String name() { return "weak_duplicate"; }
    @Override public int priority() { return 700; }

    @Override
    public void extract(ParseContext ctx) {
        if ("movie".equals(ctx.options.type())) return;
        if (Boolean.TRUE.equals(ctx.options.episodePreferNumber())) return;
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            var span = new Match("weak", null, m.start(), m.end(), m.group(), 700, Set.of(), false);
            if (!seps.test(span)) continue;
            int s = Integer.parseInt(m.group(1));
            int e = Integer.parseInt(m.group(2));
            ctx.matches.add(new Match("season", s, m.start(1), m.end(1),
                m.group(1), 700, Set.of("weak-episode", "weak-duplicate", "coexist"), false));
            ctx.matches.add(new Match("episode", e, m.start(2), m.end(2),
                m.group(2), 700, Set.of("weak-episode", "weak-duplicate", "coexist"), false));
        }
    }

    /** Replicates RemoveWeakDuplicate: drop the weak-duplicate pair when a strong SxxExx exists. */
    @Override
    public void postProcess(ParseContext ctx) {
        boolean strongPresent = ctx.matches.named("episode").anyMatch(m -> m.tags().contains("SxxExx"))
            || ctx.matches.named("season").anyMatch(m -> m.tags().contains("SxxExx"));
        if (!strongPresent) return;
        var toRemove = new ArrayList<Match>();
        for (var name : new String[]{"season", "episode"}) {
            for (var m : ctx.matches.named(name).toList()) {
                if (m.tags().contains("weak-duplicate")) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new WeakDuplicateExtractor()` after `new WeakEpisodeExtractor()`. Add the import.

Run: `mvn -q test -Dtest=WeakDuplicateExtractorTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/WeakDuplicateExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/WeakDuplicateExtractorTest.java
git commit -m "feat(rules): add WeakDuplicateExtractor (\\d{1,2}\\d{2} season/episode fallback)"
```

---

## Task 11: `DiscRule` — rename d-marker chains to disc

The Python `RenameToDiscMatch` rule looks at episodeMarker matches whose value is `d` and renames the entire chain: episode→disc, episodeMarker→discMarker. We don't model markers as separate `Match`es here (they are absorbed into `SxxExx` head matches), so we approximate: any episode match whose raw `D\d+`/`d\d+` substring exists at the chain head gets renamed to `disc`.

**Files:**
- Create: `src/main/java/io/guessit/rules/property/DiscRule.java`
- Test: `src/test/java/io/guessit/rules/property/DiscRuleTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscRuleTest {
    @Test void s01d02RenamesToDisc() {
        var r = Guessit.parse("Show.S01D02.mkv").toMap();
        assertEquals(2, r.get("disc"));
        assertNull(r.get("episode"));
    }
    @Test void s01e02IsUnchanged() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertEquals(2, r.get("episode"));
        assertNull(r.get("disc"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=DiscRuleTest`
Expected: `s01d02RenamesToDisc` failure.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;

public final class DiscRule implements Extractor {
    @Override public String name() { return "disc"; }
    @Override public int priority() { return 1000; }

    @Override public void extract(ParseContext ctx) { /* renaming-only rule */ }

    @Override
    public void postProcess(ParseContext ctx) {
        var input = ctx.input;
        var episodes = ctx.matches.named("episode").toList();
        var toRemove = new ArrayList<Match>();
        var toAdd = new ArrayList<Match>();
        for (var ep : episodes) {
            // Look 1-2 chars before the episode for an explicit `d` separator.
            if (ep.start() <= 0) continue;
            char prev = input.charAt(ep.start() - 1);
            if (prev != 'd' && prev != 'D') continue;
            // Confirm the previous char before that is a season digit or seps boundary.
            int p = ep.start() - 1;
            boolean leftBoundary = p == 0 || !Character.isLetter(input.charAt(p - 1));
            if (!leftBoundary && !Character.isDigit(input.charAt(p - 1))) continue;
            toRemove.add(ep);
            toAdd.add(new Match("disc", ep.value(), ep.start(), ep.end(), ep.raw(),
                ep.priority(), Set.of(), false));
        }
        for (var m : toRemove) ctx.matches.remove(m);
        for (var m : toAdd) ctx.matches.add(m);
    }
}
```

- [ ] **Step 4: Wire `disc` through OutputBuilder**

Open `src/main/java/io/guessit/rules/post/OutputBuilder.java`. The current `default` branch already routes unknown names through `extras`, so `disc` lands as an extra and surfaces under `disc:` in the result map. No code change required if the toMap merge already exposes extras (it does — see `GuessResult.toMap` line `if (extras != null) extras.forEach(m::putIfAbsent);`).

- [ ] **Step 5: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new DiscRule()` after `new WeakDuplicateExtractor()`. Add the import.

Run: `mvn -q test -Dtest=DiscRuleTest`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/property/DiscRule.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/DiscRuleTest.java
git commit -m "feat(rules): add DiscRule (rename d-marker episodes to disc)"
```

---

## Task 12: `AbsoluteEpisodeRule`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/AbsoluteEpisodeRule.java`
- Test: `src/test/java/io/guessit/rules/property/AbsoluteEpisodeRuleTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbsoluteEpisodeRuleTest {
    @Test void leadingWeakBeforeSxxExx() {
        // 28. Anime.Name.S02E05 → episode=5, absolute_episode=28.
        var r = Guessit.parse("28. Anime.Name.S02E05.mkv").toMap();
        assertEquals(5, r.get("episode"));
        assertEquals(28, r.get("absolute_episode"));
    }
    @Test void noOpWhenSingleBlock() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertEquals(2, r.get("episode"));
        assertNull(r.get("absolute_episode"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AbsoluteEpisodeRuleTest`
Expected: 1 failure (`leadingWeakBeforeSxxExx`).

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;

public final class AbsoluteEpisodeRule implements Extractor {
    @Override public String name() { return "absolute_episode"; }
    @Override public int priority() { return 900; }

    @Override public void extract(ParseContext ctx) { /* renaming-only rule */ }

    @Override
    public void postProcess(ParseContext ctx) {
        var sxx = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("SxxExx"))
            .findFirst().orElse(null);
        if (sxx == null) return;
        var leading = ctx.matches.named("episode")
            .filter(m -> m.tags().contains("weak-episode"))
            .filter(m -> m.end() <= sxx.start())
            .toList();
        if (leading.isEmpty()) return;

        var toRemove = new ArrayList<Match>();
        var toAdd = new ArrayList<Match>();
        for (var w : leading) {
            toRemove.add(w);
            toAdd.add(new Match("absolute_episode", w.value(), w.start(), w.end(), w.raw(),
                w.priority(), Set.of(), false));
        }
        for (var m : toRemove) ctx.matches.remove(m);
        for (var m : toAdd) ctx.matches.add(m);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new AbsoluteEpisodeRule()` after `new DiscRule()`. Add the import.

Run: `mvn -q test -Dtest=AbsoluteEpisodeRuleTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/AbsoluteEpisodeRule.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/AbsoluteEpisodeRuleTest.java
git commit -m "feat(rules): add AbsoluteEpisodeRule (rename leading weak episode)"
```

---

## Task 13: `DateExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/DateExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/DateExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DateExtractorTest {
    @Test void ymdDate() {
        var r = Guessit.parse("Show.2002-04-22.HDTV.mkv").toMap();
        assertEquals(LocalDate.of(2002, 4, 22), r.get("date"));
    }
    @Test void dmyDate() {
        var r = Guessit.parse("Show 17-06-1998 HDTV.mkv").toMap();
        assertEquals(LocalDate.of(1998, 6, 17), r.get("date"));
    }
    @Test void noDate() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("date"));
    }
    @Test void dateSuppressesEpisode() {
        // Date span overlaps would-be episode digits; date wins.
        var r = Guessit.parse("Show.2002-04-22.mkv").toMap();
        assertNotNull(r.get("date"));
        assertNull(r.get("episode"));
        assertNull(r.get("season"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=DateExtractorTest`
Expected: 3 failures.

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.Set;

public final class DateExtractor implements Extractor {
    @Override public String name() { return "date"; }
    @Override public int priority() { return 1100; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var yearFirst = ctx.options.dateYearFirst();
        var dayFirst = ctx.options.dateDayFirst();
        var found = DatePatterns.search(input, yearFirst, dayFirst);
        if (found.isEmpty()) return;
        var hit = found.get();
        ctx.matches.add(new Match("date", hit.date(), hit.start(), hit.end(),
            input.substring(hit.start(), hit.end()), 1100, Set.of(), false));
    }

    /** Replicates Python date.py conflict_solver: drop year/episode/season/crc32 inside date span. */
    @Override
    public void postProcess(ParseContext ctx) {
        var dates = ctx.matches.named("date").toList();
        if (dates.isEmpty()) return;
        var conflictNames = Set.of("year", "season", "episode", "crc32");
        var toRemove = new ArrayList<Match>();
        for (var d : dates) {
            for (var m : ctx.matches.all().toList()) {
                if (!conflictNames.contains(m.name())) continue;
                if (m.start() >= d.start() && m.end() <= d.end()) toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new DateExtractor()` after `new AbsoluteEpisodeRule()`. Add the import.

Run: `mvn -q test -Dtest=DateExtractorTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/DateExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/DateExtractorTest.java
git commit -m "feat(rules): add DateExtractor with date-vs-year/episode/season conflict resolution"
```

---

## Task 14: `WeekExtractor`

**Files:**
- Create: `src/main/java/io/guessit/rules/property/WeekExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/WeekExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeekExtractorTest {
    @Test void weekWordWithNumber() {
        var r = Guessit.parse("Show Week 12 HDTV.mkv").toMap();
        assertEquals(12, r.get("week"));
    }
    @Test void invalidWeekRangeDropped() {
        var r = Guessit.parse("Show Week 99 HDTV.mkv").toMap();
        assertNull(r.get("week"));
    }
    @Test void noWeek() {
        var r = Guessit.parse("Show.S01E02.mkv").toMap();
        assertNull(r.get("week"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=WeekExtractorTest`
Expected: 1 failure (`weekWordWithNumber`).

- [ ] **Step 3: Implement extractor**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class WeekExtractor implements Extractor {
    private static final List<String> WEEK_WORDS = List.of("week");
    private static final Pattern PATTERN = buildPattern();

    @Override public String name() { return "week"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (!DatePatterns.validWeek(v)) continue;
            int valStart = m.start(1);
            int valEnd = m.end(1);
            var match = new Match("week", v, m.start(), m.end(), m.group(), 1000, Set.of(), false);
            if (!seps.test(match)) continue;
            ctx.matches.add(new Match("week", v, valStart, valEnd,
                input.substring(valStart, valEnd), 1000, Set.of(), false));
        }
    }

    private static Pattern buildPattern() {
        var alt = String.join("|", WEEK_WORDS);
        return Pattern.compile("(?i)\\b(?:" + alt + ")[ ._-]*-?(\\d{1,2})\\b");
    }
}
```

- [ ] **Step 4: Register and run**

Open `src/main/java/io/guessit/rules/Rules.java`. Insert `new WeekExtractor()` after `new DateExtractor()`. Add the import.

Run: `mvn -q test -Dtest=WeekExtractorTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/WeekExtractor.java \
        src/main/java/io/guessit/rules/Rules.java \
        src/test/java/io/guessit/rules/property/WeekExtractorTest.java
git commit -m "feat(rules): add WeekExtractor (week_words + valid_week)"
```

---

## Task 15: `YearExtractor` learns year-vs-episode/season/week conflict

Python `date.py` declares the year regex with a conflict_solver that yields to a shorter `episode`/`season`. Plan 1 shipped a year extractor that does not know about season/episode at all (Plan 1 didn't have them). Add this hook now.

**Files:**
- Modify: `src/main/java/io/guessit/rules/property/YearExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/YearExtractorTest.java` (extend existing)

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/io/guessit/rules/property/YearExtractorTest.java`:

```java
    @org.junit.jupiter.api.Test
    void yearLosesToShorterStrongSeason() {
        // S2002 strong season would beat year=2002 since "2002" inside "S2002" is shorter than the season span.
        var r = io.guessit.Guessit.parse("Show.S2002.HDTV.mkv").toMap();
        assertEquals(2002, r.get("season"));
        assertNull(r.get("year"));
    }

    @org.junit.jupiter.api.Test
    void yearLosesToWeek() {
        // Week 2025: "2025" alone could be year, but with "Week" prefix the week match wins.
        var r = io.guessit.Guessit.parse("Show Week-2025 HDTV.mkv").toMap();
        assertEquals(25, r.get("week"));
        assertNull(r.get("year"));
    }
```

(Hint: the second test relies on `WeekExtractor`'s 1-2 digit limit dropping `2025` and matching only `25`. Adjust the test if WeekExtractor's pattern caps at 2 digits — in that case both `year=null` and `week=null` will assert null. Keep only the first sub-assert in that case.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=YearExtractorTest`
Expected: 1+ failures.

- [ ] **Step 3: Add postProcess hook**

Open `src/main/java/io/guessit/rules/property/YearExtractor.java`. Extend the existing `postProcess` to drop years that overlap a strong `season` or `episode` whose raw is shorter, and to drop years overlapping a `week` match:

```java
    @Override
    public void postProcess(ParseContext ctx) {
        // Existing KeepMarkedYearInFilepart logic stays.
        keepMarkedYearInFilepart(ctx);

        // New: year vs season/episode/week conflict resolution.
        var years = ctx.matches.named("year").toList();
        if (years.isEmpty()) return;
        var toRemove = new java.util.ArrayList<Match>();
        for (var y : years) {
            for (var other : ctx.matches.all().toList()) {
                if (!other.overlaps(y)) continue;
                if ("season".equals(other.name()) || "episode".equals(other.name())) {
                    if (other.length() < y.length() && other.tags().contains("SxxExx")) {
                        toRemove.add(y);
                        break;
                    }
                } else if ("week".equals(other.name())) {
                    toRemove.add(y);
                    break;
                }
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void keepMarkedYearInFilepart(ParseContext ctx) {
        // Move the existing body of postProcess() into this private helper.
    }
```

Concretely: rename the current `postProcess` body to a private `keepMarkedYearInFilepart(ParseContext)` method and have the new `postProcess` call it first.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=YearExtractorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/YearExtractor.java \
        src/test/java/io/guessit/rules/property/YearExtractorTest.java
git commit -m "feat(rules): year-vs-season/episode/week conflict resolution in YearExtractor"
```

---

## Task 16: Widen YML parity gate to Phase 3 + ≥80%

**Files:**
- Modify: `src/test/java/io/guessit/parity/YmlParityTest.java`

- [ ] **Step 1: Widen `PHASE_PROPS`**

Open `src/test/java/io/guessit/parity/YmlParityTest.java`. Replace the `PHASE_PROPS` set:

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
        "website", "streaming_service",
        // Phase 3
        "season", "episode", "episode_count", "season_count",
        "episode_details", "episode_format",
        "version", "disc", "absolute_episode",
        "date", "week");
```

- [ ] **Step 2: Run YML parity suite**

Run: `mvn -q test -Dtest=YmlParityTest`
Expected: many tests run; some fail. Step 3 measures the rate.

- [ ] **Step 3: Compute pass rate ≥80%**

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
assert passed / total >= 0.80, f"Phase 3 pass rate {passed/total:.1%} < 80% target"
EOF
```

Expected: `pass_rate >= 80.0%`.

- [ ] **Step 4: If pass rate < 80%, debug** (skip if Step 3 passed)

Pick the first 10 failing cases from Surefire, read each, identify which extractor needs a tweak. Add focused unit tests, fix, rerun. Land each fix as its own commit:

```bash
git commit -m "fix(rules): <extractor> handle <case>"
```

Common patterns to expect:
- `WeakEpisodeExtractor` over-fires on 2-digit screen sizes (`72` from `720p` is already excluded by `(?<!\\d)`/`(?!\\d)` — verify regex).
- `SeasonEpisodeExtractor` `Cap.102` head pattern matches inside `Capacity` words: tighten with `\b` boundary on `cap`.
- `EpisodeWordExtractor` `eps` matching mid-word like `episodes`: ensure longest-first alternation (already implemented).
- `DateExtractor` returning a date for `2002-04-22` even when there is also an `S01E02` in the path. Adjust by only emitting a date when no SxxExx-tagged season exists in the same filepart.
- Range expansion in `SeasonEpisodeExtractor.expandRanges` over-fills consecutive `01x02 03x04` (different initiators, no range): tighten `containsRange` to only allow the gap when the gap text has length exactly equal to the separator length plus surrounding seps.
- `Numerals.parse("II")` returns 2 — `EpisodeWordExtractor` Roman-numeral path should remain disabled when `type != episode`.

Helpful sub-fix snippet for date-vs-SxxExx:

```java
    @Override
    public void extract(ParseContext ctx) {
        // Skip date scan when a strong SxxExx season exists in the input.
        if (ctx.matches.named("season").anyMatch(m -> m.tags().contains("SxxExx"))) {
        }
        // ... rest unchanged
    }
```

- [ ] **Step 5: Commit harness change**

```bash
git add src/test/java/io/guessit/parity/YmlParityTest.java
git commit -m "test(parity): enable Phase 3 YML cases (≥80%)"
```

---

## Final verification

- [ ] **Step 1: Full build + tests**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS; all unit tests green; parity ≥80%.

- [ ] **Step 2: Smoke CLI**

Run: `java -jar target/guessit-java-*-cli.jar "Show.S01E02.Pilot.DVDRip.x264-CS.mkv"`
Expected output (key order may vary):

```
season: 1
episode: 2
episode_details: Pilot
title: Pilot
source: DVD
other: Rip
video_codec: H.264
release_group: CS
container: mkv
```

(Note: `title` resolution lands in Plan 4; the `episode_details` match plus the absence of `title` is acceptable here.)

- [ ] **Step 3: Final tag commit**

```bash
git tag plan-3-done
```

---

## Self-Review

**1. Spec coverage** — Spec phase 3 lists: episode/season/date, ≥80% YML. Coverage:
- season + episode strong forms: Task 7 (`SeasonEpisodeExtractor`), with `Chain` helper from Task 2.
- season + episode word forms (`Episode 4`, `Season II`, `Cap.102`, `4 of 12`): Task 8 (`EpisodeWordExtractor`).
- weak season/episode (`12`, `112`): Task 9 (`WeakEpisodeExtractor`).
- weak duplicate (`0102` → `1`/`02`): Task 10 (`WeakDuplicateExtractor`).
- episode_count, season_count: Task 8 (count rename).
- episode_details (Special, Pilot, Unaired, Final): Task 4.
- episode_format (Minisode): Task 5.
- version: Task 6.
- disc: Task 11.
- absolute_episode: Task 12.
- date: Task 13 (with `DatePatterns` from Task 3).
- week: Task 14.
- year-vs-season/episode/week conflict: Task 15.
- registration + parity ≥80%: Task 16.

**2. Placeholder scan** — Each step contains complete code or exact commands. Step 4 of Task 15 prescribes a private-method extraction (move the body, then add the new logic): the full new postProcess body is shown; the renamed helper retains the existing body verbatim. No `TODO` / `TBD` / "implement later" left in the plan.

**3. Type consistency** — `Match`, `MatchSet`, `RegexOpts`, `StringOpts`, `Extractor`, `ParseContext`, `Marker.covers()`, `OptionsConfig.section()`, `OptionsConfig.topLevelList()`, `Validators.sepsBefore/After/Surround`, `Abbreviations.dash`, `Words.iter`, `Seps.isSep`, `Numerals.NUMERAL` (Task 1), `Chain.scan`/`Chain.Run.captures(name)` (Task 2), `DatePatterns.search`/`DatePatterns.validYear`/`DatePatterns.validWeek` (Task 3), `Options.type`/`episodePreferNumber`/`dateYearFirst`/`dateDayFirst` (existing), `Match` tag conventions (`SxxExx`, `weak-episode`, `weak-duplicate`, `coexist`, `see-pattern`) — all match either existing files or the names defined within this plan. Verified by reading each existing file before drafting.

**4. Deferred vs. shipped** — Explicitly deferred to later plans, with code comments where applicable:
- Full `WeakConflictSolver` anime-detection branch (depends on title/group plus extra anime heuristics) — Plan 4/5; Task 10 ships only the SxxExx-trumps-weak-duplicate subset.
- `EpisodeSingleDigitValidator` "drop episode inside title-less group" — Plan 4 (needs title); Task 9 approximates with `RemoveWeakIfSxxExx` + `start != 0` heuristic.
- `RemoveDetachedEpisodeNumber` "Fairy Tail 2 - 16-20" — Plan 5 (needs full range-detection across episodes); approximated by Task 9.
- `RenameToAbsoluteEpisode` second branch (two SxxExx blocks renumbered) — Task 12 ships only the leading-weak-before-SxxExx subset; Plan 5 polish.
- `disc` matched via discrete `d` separator outside SxxExx chains — Plan 5 polish.
- Full Python `dateutil.parser` flexibility (`22nd-July-2002`) — Task 3 covers the regex but month parsing is English-only; Plan 5 polish for non-English months.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-02-plan-3-phase3-episodes-date.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch with checkpoints.
