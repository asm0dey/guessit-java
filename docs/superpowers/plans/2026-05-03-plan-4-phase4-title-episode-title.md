# Plan 4: Phase 4 Extractors — title, alternative_title, episode_title, type

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Phase 4 extractors against the foundation laid in Plans 0–3. End state: `Rules.allInOrder()` registers `TitleExtractor` and `EpisodeTitleExtractor` after `ReleaseGroupExtractor`; the legacy `TitleMarkerSelector` is removed; a new `TypeProcessor` runs in `PostPhase` and emits a `type` match (`movie`|`episode`) read by `OutputBuilder`. Per-rule unit tests pass; the YML parity gate widens to include `title`, `alternative_title`, `episode_title`, and `type` plus the Phase 1+2+3 props, and ≥95% of YML cases pass.

**Architecture:** New shared helpers under `io.guessit.engine`:
- `Formatters` — `cleanup`, `reorderTitle`, `strip`, `titleText` (Python `rules/common/formatters.py`).
- `Holes` — `Hole` mutable holder + `compute(...)` + per-hole `crop`/`split` (Python rebulk's `Matches.holes()` minimal subset).
- `Markers` — `named`, `atMatch`, `coveringMatch`, `markerSorted` (Python `matches.markers.named()` / `at_match` / `marker_sorted`).
- `MatchSet` additions: `range`, `previous`, `next`, `chainBefore`, `chainAfter`, `tagged`, `snapshot`.
- `Seps.TITLE_CHARS` constant (`-+/\|`, Python `title_seps`).

Two extractors land in `io.guessit.rules.property`:
- `TitleExtractor` — `extract(ctx)` runs the `expected_title` functional (Options-driven); `postProcess(ctx)` runs the ported `TitleBaseRule` + `TitleFromPosition` + `PreferTitleWithYear`. Title and alternative_title both come out as real `Match`es.
- `EpisodeTitleExtractor` — `postProcess(ctx)` only. Runs six chained rules: `RemoveConflictsWithEpisodeTitle`, `TitleToEpisodeTitle`, `EpisodeTitleFromPosition`, `AlternativeTitleReplace`, `Filepart3EpisodeTitle`, `Filepart2EpisodeTitle`.

A new `TypeProcessor` (PostProcessor) runs in `PostPhase` between `PreferLastPath` and `PrivateRemover`, decides `type` per Python `properties/type.py` and (inline) demotes any non-`alternative-replaced` `episode_title` to `alternative_title` when `type != "episode"` (Python `RenameEpisodeTitleWhenMovieType`).

`TitleMarkerSelector` and `ParseContext.titleMarker` are deleted — the title is now a real match.

**Tech Stack:** Same as Plans 0/1/2/3 — Java 25, JUnit Jupiter 5.12.x, Apache Commons CSV, Jackson + SnakeYAML, AssertJ, no new dependencies.

**Reference source:**
- Python: `/tmp/guessit/guessit/rules/properties/{title,episode_title,type,film}.py`
- Python helpers: `/tmp/guessit/guessit/rules/common/{formatters.py,__init__.py,validators.py,pattern.py}`
- Python rebulk: `/tmp/rebulk/rebulk/match.py` (especially `holes()`, `crop()`, `split()`, `chain_before`, `chain_after`, `previous`, `at_match`, `at_index`)
- Spec: `docs/superpowers/specs/2026-05-02-guessit-java-design.md`
- Plan 0–3: `docs/superpowers/plans/2026-05-02-plan-{0..3}-*.md`

---

## File Structure

Created in this plan (paths relative to repo root):

```
src/main/java/io/guessit/
├── engine/
│   ├── Formatters.java                             // cleanup, reorderTitle, strip
│   ├── Holes.java                                  // Hole + compute + crop + split
│   └── Markers.java                                // named, atMatch, markerSorted
└── rules/
    ├── post/
    │   └── TypeProcessor.java                      // type=movie|episode + episode_title rename
    └── property/
        ├── TitleExtractor.java
        └── EpisodeTitleExtractor.java

src/test/java/io/guessit/
├── engine/
│   ├── FormattersTest.java
│   ├── HolesTest.java
│   └── MarkersTest.java
└── rules/
    ├── post/
    │   └── TypeProcessorTest.java
    └── property/
        ├── TitleExtractorTest.java
        └── EpisodeTitleExtractorTest.java
```

Modified in this plan:

```
src/main/java/io/guessit/engine/MatchSet.java      // range/previous/next/chainBefore/chainAfter/tagged
src/main/java/io/guessit/engine/Seps.java          // add TITLE_CHARS
src/main/java/io/guessit/engine/ParseContext.java  // remove titleMarker field
src/main/java/io/guessit/rules/Rules.java          // register Phase 4 + swap TitleMarkerSelector→TypeProcessor
src/test/java/io/guessit/engine/MatchSetTest.java  // tests for new helpers
src/test/java/io/guessit/parity/YmlParityTest.java // widen PHASE_PROPS, raise threshold
```

Deleted in this plan:

```
src/main/java/io/guessit/rules/post/TitleMarkerSelector.java
```

Responsibilities (one per file):
- `engine/Formatters` — pure functions over strings; no state. Mirrors Python `cleanup`, `reorder_title`, `strip`.
- `engine/Holes` — `Hole` is a small mutable holder (`int start, end; String input; Function<String,String> formatter`); `Hole.value()` lazy-computes formatted text. `compute(...)` walks `[start,end)` and emits a hole per gap between non-ignored matches. `crop(List<Marker>)` and `split(String seps)` mirror rebulk's spans operations.
- `engine/Markers` — static helpers; takes `List<Marker>` (the `ParseContext.markers` field).
- `engine/MatchSet` (extended) — query helpers backed by the existing `ArrayList<Match>`.
- `engine/Seps` (extended) — adds `TITLE_CHARS` (`-+/\|`).
- `engine/ParseContext` — drop `titleMarker`; the field is no longer read or written anywhere.
- `rules/post/TypeProcessor` — implements `PostProcessor`; decides `type`; emits zero-width `type` match at end of input; renames `episode_title` to `alternative_title` for non-episode types.
- `rules/property/TitleExtractor` — `extract` for expected_title, `postProcess` for position-based title.
- `rules/property/EpisodeTitleExtractor` — `postProcess` only; six sub-rules in fixed order.
- `rules/Rules.allInOrder()` — append `TitleExtractor`, `EpisodeTitleExtractor` after `ReleaseGroupExtractor`. `defaultPipeline()` PostPhase order: `PreferLastPath`, `TypeProcessor`, `PrivateRemover` (no `TitleMarkerSelector`).

---

## Task 1: `engine/Seps.TITLE_CHARS` constant

**Files:**
- Modify: `src/main/java/io/guessit/engine/Seps.java`

- [ ] **Step 1: Add the constant**

In `Seps.java`, just after the `CHARS` constant declaration:

```java
/** Python guessit's title_seps — the subset used to split a hole into title + alternative_title. */
public static final String TITLE_CHARS = "-+/\\|";
```

- [ ] **Step 2: Verify existing tests still pass**

Run: `mvn -q test -Dtest=SepsTest`
Expected: PASS — no behavioural change.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/Seps.java
git commit -m "feat(engine): add Seps.TITLE_CHARS for title/alternative_title splitting"
```

---

## Task 2: `engine/Formatters` — cleanup + reorderTitle + strip

**Files:**
- Create: `src/main/java/io/guessit/engine/Formatters.java`
- Test: `src/test/java/io/guessit/engine/FormattersTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormattersTest {
    @Test void cleanupReplacesSepsWithSpacesAndCollapses() {
        assertEquals("Movie Name", Formatters.cleanup("Movie.Name"));
        assertEquals("Movie Name", Formatters.cleanup("  Movie___Name  "));
        assertEquals("a b c", Formatters.cleanup("a..b..c"));
    }
    @Test void cleanupKeepsCommasColonsDashesSlashes() {
        // Python excludes ,:;-/\ from the replacement set, so they survive cleanup.
        assertEquals("a,b", Formatters.cleanup("a,b"));
        assertEquals("a-b", Formatters.cleanup("a-b"));
        assertEquals("a:b", Formatters.cleanup("a:b"));
    }
    @Test void cleanupRestoresSingleCharDottedRuns() {
        // S.H.I.E.L.D. survives because each dot separates single chars on both sides.
        assertEquals("Marvels Agents of S.H.I.E.L.D",
            Formatters.cleanup("Marvels.Agents.of.S.H.I.E.L.D"));
    }
    @Test void reorderTitlePromotesArticle() {
        assertEquals("The Matrix", Formatters.reorderTitle("Matrix, The"));
        assertEquals("The Matrix", Formatters.reorderTitle("Matrix,The"));
    }
    @Test void reorderTitleNoOpWhenNoArticle() {
        assertEquals("The Matrix", Formatters.reorderTitle("The Matrix"));
        assertEquals("Foo Bar", Formatters.reorderTitle("Foo Bar"));
    }
    @Test void stripRemovesSepsFromBothEnds() {
        assertEquals("foo", Formatters.strip(".. foo --"));
    }
    @Test void titleTextChainsCleanupAndReorder() {
        assertEquals("The Matrix", Formatters.titleText("Matrix..The"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FormattersTest`
Expected: FAIL — class `Formatters` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

public final class Formatters {
    private Formatters() {}

    private static final String EXCLUDED_CLEAN_CHARS = ",:;-/\\";

    private static final String CLEAN_CHARS;
    static {
        var sb = new StringBuilder();
        for (var c : Seps.CHARS.toCharArray()) {
            if (EXCLUDED_CLEAN_CHARS.indexOf(c) < 0) sb.append(c);
        }
        CLEAN_CHARS = sb.toString();
    }

    public static String strip(String s) {
        if (s == null || s.isEmpty()) return s;
        var start = 0;
        var end = s.length();
        while (start < end && Seps.isSep(s.charAt(start))) start++;
        while (end > start && Seps.isSep(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    public static String strip(String s, String chars) {
        if (s == null || s.isEmpty()) return s;
        var start = 0;
        var end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    public static String cleanup(String input) {
        if (input == null || input.isEmpty()) return input;
        var clean = new StringBuilder(input.length());
        for (var c : input.toCharArray()) {
            clean.append(CLEAN_CHARS.indexOf(c) >= 0 ? ' ' : c);
        }
        var cleanString = clean.toString();

        var indices = new java.util.ArrayList<Integer>();
        for (var i = 0; i < cleanString.length(); i++) {
            if (Seps.isSep(cleanString.charAt(i))) indices.add(i);
        }
        var dots = new java.util.HashSet<Character>();
        if (!indices.isEmpty()) {
            var chars = cleanString.toCharArray();
            var potential = new java.util.ArrayList<Integer>();
            for (var i : indices) {
                if (potentialBefore(i, input) && potentialAfter(i, input)) potential.add(i);
            }
            var replace = new java.util.ArrayList<Integer>();
            for (var p : potential) {
                if (potential.contains(p - 2) || potential.contains(p + 2)) replace.add(p);
            }
            if (!replace.isEmpty()) {
                for (var r : replace) {
                    dots.add(input.charAt(r));
                    chars[r] = input.charAt(r);
                }
                cleanString = new String(chars);
            }
        }

        var stripChars = new StringBuilder();
        for (var c : Seps.CHARS.toCharArray()) {
            if (!dots.contains(c)) stripChars.append(c);
        }
        cleanString = strip(cleanString, stripChars.toString());
        return cleanString.replaceAll(" +", " ");
    }

    private static boolean potentialBefore(int i, String input) {
        return i - 1 >= 0 && i < input.length()
            && Seps.isSep(input.charAt(i))
            && i - 2 >= 0 && Seps.isSep(input.charAt(i - 2))
            && !Seps.isSep(input.charAt(i - 1));
    }

    private static boolean potentialAfter(int i, String input) {
        if (i + 2 >= input.length()) return true;
        return input.charAt(i + 2) == input.charAt(i) && !Seps.isSep(input.charAt(i + 1));
    }

    public static String reorderTitle(String title) {
        if (title == null) return null;
        var ltitle = title.toLowerCase(java.util.Locale.ROOT);
        for (var article : new String[]{"the"}) {
            for (var separator : new String[]{",", ", "}) {
                var suffix = separator + article;
                if (ltitle.endsWith(suffix)) {
                    return title.substring(title.length() - suffix.length() + separator.length())
                        + " " + title.substring(0, title.length() - suffix.length());
                }
            }
        }
        return title;
    }

    public static String titleText(String input) {
        if (input == null) return null;
        return reorderTitle(cleanup(input));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=FormattersTest`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Formatters.java src/test/java/io/guessit/engine/FormattersTest.java
git commit -m "feat(engine): add Formatters (cleanup, reorderTitle, strip) for title text"
```

---

## Task 3: `engine/Holes` — Hole + compute + crop + split

**Files:**
- Create: `src/main/java/io/guessit/engine/Holes.java`
- Test: `src/test/java/io/guessit/engine/HolesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HolesTest {
    @Test void computeReturnsGapsBetweenMatches() {
        var input = "Movie.Name.2020.1080p.x264";
        var matches = List.of(
            Match.of("year", 2020, 11, 15, "2020"),
            Match.of("screen_size", "1080p", 16, 21, "1080p"),
            Match.of("video_codec", "H.264", 22, 26, "x264"));
        var holes = Holes.compute(input, 0, input.length(), matches, m -> false, null, Formatters::cleanup);
        assertEquals(1, holes.size());
        assertEquals("Movie Name", holes.get(0).value());
    }
    @Test void ignoredMatchesAreTransparent() {
        var input = "Hello.world.bar";
        var matches = List.of(Match.of("language", "en", 6, 11, "world"));
        var holes = Holes.compute(input, 0, input.length(), matches,
            m -> m.name().equals("language"), null, Formatters::cleanup);
        assertEquals(1, holes.size());
        assertEquals("Hello world bar", holes.get(0).value());
    }
    @Test void cropAroundMarker() {
        var input = "abc[def]ghi";
        var hole = new Holes.Hole(0, 11, input, Formatters::cleanup);
        var cropped = hole.crop(List.of(new Marker("group", 3, 8, "[def]")));
        assertEquals(2, cropped.size());
        assertEquals("abc", cropped.get(0).value());
        assertEquals("ghi", cropped.get(1).value());
    }
    @Test void splitOnTitleSeps() {
        var input = "Foo-Bar/Baz";
        var hole = new Holes.Hole(0, 11, input, s -> s);
        var parts = hole.split(Seps.TITLE_CHARS);
        assertEquals(List.of("Foo", "Bar", "Baz"),
            parts.stream().map(Holes.Hole::raw).toList());
    }
    @Test void emptyHoleSkipped() {
        var input = "ab";
        var matches = List.of(Match.of("x", null, 0, 2, "ab"));
        var holes = Holes.compute(input, 0, input.length(), matches, m -> false, null, Formatters::cleanup);
        assertTrue(holes.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=HolesTest`
Expected: FAIL — class `Holes` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Holes {
    private Holes() {}

    public static final class Hole {
        public int start;
        public int end;
        public final String input;
        public final Function<String, String> formatter;

        public Hole(int start, int end, String input, Function<String, String> formatter) {
            this.start = start; this.end = end;
            this.input = input; this.formatter = formatter;
        }

        public String raw() { return input.substring(start, end); }
        public String value() { return formatter == null ? raw() : formatter.apply(raw()); }
        public boolean isEmpty() { var v = value(); return v == null || v.isEmpty(); }
        public int length() { return end - start; }

        public List<Hole> crop(List<Marker> markers) {
            var ret = new ArrayList<Hole>();
            ret.add(this);
            for (var m : markers) {
                var newRet = new ArrayList<Hole>();
                for (var h : ret) {
                    if (m.start() <= h.start && m.end() >= h.end) {
                        // marker fully covers hole - drop
                    } else if (m.start() >= h.start && m.end() <= h.end) {
                        var left = new Hole(h.start, m.start(), input, formatter);
                        var right = new Hole(m.end(), h.end, input, formatter);
                        if (left.length() > 0) newRet.add(left);
                        if (right.length() > 0) newRet.add(right);
                    } else if (m.end() >= h.end && m.start() < h.end && m.start() > h.start) {
                        h.end = m.start();
                        if (h.length() > 0) newRet.add(h);
                    } else if (m.start() <= h.start && m.end() > h.start && m.end() < h.end) {
                        h.start = m.end();
                        if (h.length() > 0) newRet.add(h);
                    } else {
                        newRet.add(h);
                    }
                }
                ret = newRet;
            }
            return ret;
        }

        public List<Hole> split(String seps) {
            var ret = new ArrayList<Hole>();
            var raw = raw();
            int i = 0;
            while (i < raw.length()) {
                while (i < raw.length() && seps.indexOf(raw.charAt(i)) >= 0) i++;
                int s = i;
                while (i < raw.length() && seps.indexOf(raw.charAt(i)) < 0) i++;
                if (s < i) {
                    var sub = new Hole(start + s, start + i, input, formatter);
                    if (!sub.isEmpty()) ret.add(sub);
                }
            }
            return ret;
        }
    }

    public static List<Hole> compute(String input, int start, int end,
                                     List<Match> allMatches,
                                     Predicate<Match> ignore,
                                     String seps,
                                     Function<String, String> formatter) {
        var matches = new ArrayList<>(allMatches);
        matches.sort(Comparator.comparingInt(Match::start));
        var active = new ArrayList<Match>();
        for (var m : matches) {
            if (ignore != null && ignore.test(m)) continue;
            if (m.end() <= start || m.start() >= end) continue;
            active.add(m);
        }

        var ret = new ArrayList<Hole>();
        Hole current = null;
        for (var pos = start; pos < end; pos++) {
            var inMatch = false;
            for (var m : active) {
                if (m.start() <= pos && pos < m.end()) { inMatch = true; break; }
            }
            if (current != null && seps != null && pos < input.length()
                    && seps.indexOf(input.charAt(pos)) >= 0) {
                current.end = pos;
                if (current.length() > 0) ret.add(current);
                current = null;
            } else if (!inMatch && current == null) {
                current = new Hole(pos, pos, input, formatter);
            } else if (inMatch && current != null) {
                current.end = pos;
                if (current.length() > 0) ret.add(current);
                current = null;
            }
        }
        if (current != null) {
            current.end = end;
            if (current.length() > 0) ret.add(current);
        }
        ret.removeIf(h -> { var v = h.value(); return v == null || v.isEmpty(); });
        return ret;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=HolesTest`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Holes.java src/test/java/io/guessit/engine/HolesTest.java
git commit -m "feat(engine): add Holes (compute, crop, split) for title hole detection"
```

---

## Task 4: `engine/MatchSet` query helpers (range/previous/next/chainBefore/chainAfter/tagged)

**Files:**
- Modify: `src/main/java/io/guessit/engine/MatchSet.java`
- Modify: `src/test/java/io/guessit/engine/MatchSetTest.java`

- [ ] **Step 1: Add tests for the new helpers**

In `MatchSetTest.java`, append:

```java
@Test
void rangeReturnsMatchesFullyInsideSpan() {
    var set = new MatchSet();
    set.add(Match.of("a", 1, 0, 5, "00000"));
    set.add(Match.of("b", 2, 6, 10, "1111"));
    set.add(Match.of("c", 3, 11, 15, "2222"));
    var inRange = set.range(0, 10, m -> true).toList();
    assertEquals(2, inRange.size());
    assertEquals("a", inRange.get(0).name());
    assertEquals("b", inRange.get(1).name());
}

@Test
void previousAndNextRespectPredicate() {
    var set = new MatchSet();
    var a = Match.of("a", 1, 0, 3, "aaa");
    var b = Match.of("b", 2, 5, 8, "bbb");
    var c = Match.of("c", 3, 10, 13, "ccc");
    set.add(a); set.add(b); set.add(c);
    assertEquals(a, set.previous(b, m -> true).orElseThrow());
    assertEquals(c, set.next(b, m -> true).orElseThrow());
    assertTrue(set.previous(a, m -> true).isEmpty());
}

@Test
void chainBeforeWalksOnlyThroughSeps() {
    var input = "abc.def-ghi";
    var set = new MatchSet();
    var a = Match.of("a", 1, 0, 3, "abc");
    var b = Match.of("b", 2, 4, 7, "def");
    set.add(a); set.add(b);
    assertEquals(b, set.chainBefore(11, input, " ._-", m -> true).orElseThrow());
    assertEquals(a, set.chainBefore(4, input, " ._-", m -> true).orElseThrow());
}

@Test
void chainAfterWalksOnlyThroughSeps() {
    var input = "abc.def-ghi";
    var set = new MatchSet();
    var b = Match.of("b", 2, 4, 7, "def");
    var c = Match.of("c", 3, 8, 11, "ghi");
    set.add(b); set.add(c);
    assertEquals(b, set.chainAfter(0, input, " ._-", m -> true).orElseThrow());
    assertEquals(c, set.chainAfter(7, input, " ._-", m -> true).orElseThrow());
}

@Test
void taggedFiltersByTagSet() {
    var set = new MatchSet();
    set.add(new Match("a", null, 0, 1, "a", 1000, java.util.Set.of("foo"), false));
    set.add(Match.of("b", null, 2, 3, "b"));
    var tagged = set.tagged("foo").toList();
    assertEquals(1, tagged.size());
    assertEquals("a", tagged.get(0).name());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=MatchSetTest`
Expected: FAIL — methods do not exist.

- [ ] **Step 3: Add the helpers**

In `MatchSet.java`, add `Comparator`, `Optional`, `Predicate` imports if missing; add:

```java
public Stream<Match> range(int start, int end, Predicate<Match> p) {
    return matches.stream()
        .filter(m -> m.start() >= start && m.end() <= end)
        .filter(p);
}

public Optional<Match> previous(Match m, Predicate<Match> p) {
    return matches.stream()
        .filter(o -> o.end() <= m.start())
        .filter(p)
        .max(Comparator.comparingInt(Match::end));
}

public Optional<Match> next(Match m, Predicate<Match> p) {
    return matches.stream()
        .filter(o -> o.start() >= m.end())
        .filter(p)
        .min(Comparator.comparingInt(Match::start));
}

public Optional<Match> chainBefore(int pos, String input, String seps, Predicate<Match> p) {
    var i = pos - 1;
    while (i >= 0 && seps.indexOf(input.charAt(i)) >= 0) i--;
    if (i < 0) return Optional.empty();
    var endPos = i + 1;
    return matches.stream().filter(m -> m.end() == endPos).filter(p).findFirst();
}

public Optional<Match> chainAfter(int pos, String input, String seps, Predicate<Match> p) {
    var i = pos;
    while (i < input.length() && seps.indexOf(input.charAt(i)) >= 0) i++;
    if (i >= input.length()) return Optional.empty();
    var startPos = i;
    return matches.stream().filter(m -> m.start() == startPos).filter(p).findFirst();
}

public Stream<Match> tagged(String tag) {
    return matches.stream().filter(m -> m.tags().contains(tag));
}

public List<Match> snapshot() { return List.copyOf(matches); }
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=MatchSetTest`
Expected: PASS — all tests including the 5 new ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/MatchSet.java src/test/java/io/guessit/engine/MatchSetTest.java
git commit -m "feat(engine): add MatchSet query helpers (range/previous/next/chainBefore/chainAfter/tagged)"
```

---

## Task 5: `engine/Markers` — named/atMatch/markerSorted

**Files:**
- Create: `src/main/java/io/guessit/engine/Markers.java`
- Test: `src/test/java/io/guessit/engine/MarkersTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkersTest {
    @Test void namedFiltersByName() {
        var markers = List.of(
            new Marker("path", 0, 5, "12345"),
            new Marker("group", 1, 4, "234"),
            new Marker("path", 6, 10, "6789"));
        var paths = Markers.named(markers, "path").toList();
        assertEquals(2, paths.size());
    }
    @Test void atMatchReturnsContainingMarker() {
        var markers = List.of(new Marker("path", 0, 10, "0123456789"));
        var m = Match.of("x", null, 2, 5, "234");
        assertEquals(markers.get(0), Markers.atMatch(markers, m, mk -> true).orElseThrow());
    }
    @Test void markerSortedByDescendingMatchCount() {
        var p1 = new Marker("path", 0, 5, "0..4");
        var p2 = new Marker("path", 6, 12, "6..11");
        var matches = new MatchSet();
        matches.add(Match.of("a", null, 6, 7, "a"));
        matches.add(Match.of("b", null, 8, 9, "b"));
        matches.add(Match.of("c", null, 10, 11, "c"));
        matches.add(Match.of("d", null, 0, 1, "d"));
        var sorted = Markers.markerSorted(List.of(p1, p2), matches);
        assertEquals(p2, sorted.get(0));
        assertEquals(p1, sorted.get(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=MarkersTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class Markers {
    private Markers() {}

    public static Stream<Marker> named(List<Marker> markers, String name) {
        return markers.stream().filter(m -> m.name().equals(name));
    }

    public static Stream<Marker> coveringMatch(List<Marker> markers, Match m, Predicate<Marker> p) {
        return markers.stream()
            .filter(mk -> mk.covers(m.start(), m.end()))
            .filter(p)
            .sorted(Comparator.comparingInt(Marker::start));
    }

    public static Optional<Marker> atMatch(List<Marker> markers, Match m, Predicate<Marker> p) {
        return coveringMatch(markers, m, p).findFirst();
    }

    public static List<Marker> markerSorted(List<Marker> paths, MatchSet matches) {
        var indexed = new ArrayList<int[]>();
        for (var i = 0; i < paths.size(); i++) {
            var p = paths.get(i);
            var count = (int) matches.all().filter(x -> p.covers(x.start(), x.end())).count();
            indexed.add(new int[]{i, count});
        }
        indexed.sort((a, b) -> {
            var byCount = Integer.compare(b[1], a[1]);
            return byCount != 0 ? byCount : Integer.compare(a[0], b[0]);
        });
        var ret = new ArrayList<Marker>();
        for (var entry : indexed) ret.add(paths.get(entry[0]));
        return ret;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=MarkersTest`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Markers.java src/test/java/io/guessit/engine/MarkersTest.java
git commit -m "feat(engine): add Markers (named, atMatch, markerSorted) helpers"
```

---

## Task 6: `rules/post/TypeProcessor` — decide type and demote episode_title for movies

**Files:**
- Create: `src/main/java/io/guessit/rules/post/TypeProcessor.java`
- Test: `src/test/java/io/guessit/rules/post/TypeProcessorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.post;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeProcessorTest {
    private static ParseContext ctx(String input) {
        return new ParseContext(input, Options.defaults(), OptionsConfig.empty());
    }

    @Test void seasonOrEpisodeYieldsEpisode() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of("season", 1, 0, 2, "S1"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("episode");
    }
    @Test void noEpisodeOrYearYieldsMovie() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of("year", 2020, 0, 4, "2020"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("movie");
    }
    @Test void dateWithoutYearYieldsEpisode() {
        var ctx = ctx("anything");
        ctx.matches.add(Match.of("date", null, 0, 10, "2020-01-01"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("episode");
    }
    @Test void optionsTypeOverridesEverything() {
        var opts = Options.builder().type("movie").build();
        var ctx = new ParseContext("x", opts, OptionsConfig.empty());
        ctx.matches.add(Match.of("episode", 1, 0, 1, "1"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("type").findFirst().orElseThrow().value()).isEqualTo("movie");
    }
    @Test void movieTypeRenamesEpisodeTitleToAlternativeTitle() {
        var ctx = ctx("Some Movie");
        ctx.matches.add(Match.of("year", 2020, 0, 4, "2020"));
        ctx.matches.add(Match.of("episode_title", "Bonus", 5, 10, "Bonus"));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("episode_title").count()).isZero();
        assertThat(ctx.matches.named("alternative_title").findFirst().orElseThrow().value()).isEqualTo("Bonus");
    }
    @Test void episodeTitleWithAlternativeReplacedTagSurvivesMovieDemotion() {
        var ctx = ctx("Some Movie");
        ctx.matches.add(Match.of("year", 2020, 0, 4, "2020"));
        ctx.matches.add(new Match("episode_title", "X", 5, 6, "X", 1000,
            java.util.Set.of("alternative-replaced"), false));
        new TypeProcessor().process(ctx);
        assertThat(ctx.matches.named("episode_title").count()).isOne();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=TypeProcessorTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

/**
 * Decide {@code type} (movie|episode) from surviving matches and emit a
 * zero-width {@code type} match at the end of the input, mirroring Python
 * guessit's {@code TypeProcessor}. Then demote any {@code episode_title}
 * (without the {@code alternative-replaced} tag) back to {@code alternative_title}
 * when the chosen type is not {@code episode} (Python {@code RenameEpisodeTitleWhenMovieType}).
 */
public final class TypeProcessor implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        var type = decide(ctx);
        var len = ctx.input.length();
        ctx.matches.add(Match.of("type", type, len, len, ""));
        if (!"episode".equals(type)) {
            var toRename = ctx.matches.named("episode_title")
                .filter(m -> !m.tags().contains("alternative-replaced"))
                .toList();
            for (var m : toRename) {
                ctx.matches.replace(m, new Match("alternative_title", m.value(),
                    m.start(), m.end(), m.raw(), m.priority(), m.tags(), m.isPrivate()));
            }
        }
    }

    private static String decide(ParseContext ctx) {
        var optType = ctx.options.type();
        if (optType != null) return optType;
        if (anyNamed(ctx, "episode") || anyNamed(ctx, "season")
                || anyNamed(ctx, "episode_details") || anyNamed(ctx, "absolute_episode")) {
            return "episode";
        }
        if (anyNamed(ctx, "film")) return "movie";
        var hasYear = anyNamed(ctx, "year");
        if (anyNamed(ctx, "date") && !hasYear) return "episode";
        if (anyNamed(ctx, "bonus") && !hasYear) return "episode";
        var hasCrc = anyNamed(ctx, "crc32");
        var anyAnimeRg = ctx.matches.named("release_group").anyMatch(m -> m.tags().contains("anime"));
        if (hasCrc && anyAnimeRg) return "episode";
        return "movie";
    }

    private static boolean anyNamed(ParseContext ctx, String name) {
        return ctx.matches.named(name).findAny().isPresent();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=TypeProcessorTest`
Expected: PASS — 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/post/TypeProcessor.java src/test/java/io/guessit/rules/post/TypeProcessorTest.java
git commit -m "feat(post): add TypeProcessor (movie|episode + episode_title demotion)"
```

---

## Task 7: `rules/property/TitleExtractor` — title + alternative_title from filepart holes

**Files:**
- Create: `src/main/java/io/guessit/rules/property/TitleExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/TitleExtractorTest.java`

The extractor reproduces three Python rules, in this order:

1. The `expected_title` functional (in `extract`) — emit a `title` match for each user-provided expected_title that occurs in the input, validated by `Validators.sepsSurround`.
2. `TitleFromPosition` (in `postProcess`) — pick the path marker(s) producing the most matches, compute the cleaned hole, set its name to `title`. Apply `serie_name_filepart` lookup for `Show/Season N/...` paths. Split the hole on `Seps.TITLE_CHARS` to spawn `alternative_title` matches.
3. `PreferTitleWithYear` (also in `postProcess`) — among emitted `title` matches, prefer those in fileparts containing a `year`; drop the others.

The TitleBaseRule "is_ignored" predicate stays in this class as a private helper so EpisodeTitleExtractor can reuse it (export as `package-private static`).

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleExtractorTest {
    @Test void simpleFilepartHoleBecomesTitle() {
        var r = Guessit.parse("Movie.Name.2020.1080p.BluRay-RG.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Movie Name");
    }
    @Test void dashSplitYieldsAlternativeTitle() {
        var r = Guessit.parse("Main Title - Alt Title.2020.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Main Title");
        assertThat(r.get("alternative_title")).isEqualTo("Alt Title");
    }
    @Test void serieNameFilepartRoutesInnerToEpisodeTitle() {
        var r = Guessit.parse("Caprica/Season 1/Apotheosis.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Caprica");
        assertThat(r.get("episode_title")).isEqualTo("Apotheosis");
        assertThat(r.get("season")).isEqualTo(1);
    }
    @Test void preferTitleWithYearFilepart() {
        var r = Guessit.parse("Foo/Movie.Name.2020.1080p.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Movie Name");
    }
    @Test void expectedTitleEmitsExpectedTaggedMatch() {
        var opts = Options.builder().expectedTitle(java.util.List.of("My Show")).build();
        var r = Guessit.parse("My.Show.2020.mkv", opts).toMap();
        assertThat(r.get("title")).isEqualTo("My Show");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=TitleExtractorTest`
Expected: FAIL — class does not exist; existing pipeline cannot produce title.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Port of Python {@code rules/properties/title.py}: emits {@code title} and
 * {@code alternative_title} matches.
 *
 * <p>Three sub-rules:
 * <ul>
 *   <li><b>expected_title functional</b> in {@link #extract} — emits a {@code title}
 *       match for each {@code Options#expectedTitle} substring found in the input.</li>
 *   <li><b>TitleFromPosition</b> in {@link #postProcess} — for the highest-scoring path
 *       marker, takes the cleaned hole as a {@code title} match. Splits the hole on
 *       {@link Seps#TITLE_CHARS} to produce {@code alternative_title} matches. Routes
 *       inner-filepart titles to {@code episode_title} when an outer "Show/Season N"
 *       filepart shape is detected.</li>
 *   <li><b>PreferTitleWithYear</b> in {@link #postProcess} — prefers titles in the
 *       filepart containing a year; drops the others.</li>
 * </ul>
 */
public final class TitleExtractor implements Extractor {
    static final Set<String> NON_SPECIFIC_LANGUAGES = Set.of("mul", "und");

    @Override
    public String name() { return "title"; }

    @Override
    public void extract(ParseContext ctx) {
        var expected = ctx.options.expectedTitle();
        if (expected.isEmpty()) return;
        var input = ctx.input;
        var sepsSurround = Validators.sepsSurround(input);
        for (var word : expected) {
            int idx = 0;
            while ((idx = input.indexOf(word, idx)) >= 0) {
                var raw = input.substring(idx, idx + word.length());
                var formatted = Formatters.titleText(raw);
                var m = new Match("title", formatted, idx, idx + word.length(), raw,
                    1000, Set.of("expected", "title"), false);
                if (sepsSurround.test(m)) ctx.matches.add(m);
                idx += word.length();
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var hasExpected = ctx.matches.named("title").anyMatch(m -> m.tags().contains("expected"));
        if (!hasExpected) {
            titleFromPosition(ctx);
        }
        preferTitleWithYear(ctx);
    }

    private void titleFromPosition(ParseContext ctx) {
        var paths = ctx.markers.stream().filter(m -> "path".equals(m.name())).toList();
        if (paths.isEmpty()) return;
        var sorted = Markers.markerSorted(paths, ctx.matches);

        var serieNameFilepart = serieNameFilepart(ctx, paths);
        Match serieNameTitle = null;
        if (serieNameFilepart != null) {
            var titles = checkTitlesInFilepart(ctx, serieNameFilepart, this::serieNameIgnored);
            if (titles != null && titles.titles.size() == 1) {
                for (var t : titles.titles) ctx.matches.add(t);
                for (var r : titles.toRemove) ctx.matches.remove(r);
                serieNameTitle = titles.titles.get(0);
            }
        }

        var yearFileparts = paths.stream()
            .filter(fp -> ctx.matches.range(fp.start(), fp.end(), m -> "year".equals(m.name())).findAny().isPresent())
            .toList();
        var consumedYearFileparts = new HashSet<Marker>();

        for (var fp : sorted) {
            consumedYearFileparts.add(fp);
            var titles = checkTitlesInFilepart(ctx, fp, m -> false);
            if (titles == null) continue;
            for (var t : titles.titles) {
                if (serieNameTitle != null && !java.util.Objects.equals(t.value(), serieNameTitle.value())) {
                    ctx.matches.add(new Match("episode_title", t.value(), t.start(), t.end(),
                        t.raw(), t.priority(), Set.of("title"), false));
                } else {
                    ctx.matches.add(t);
                }
            }
            for (var r : titles.toRemove) ctx.matches.remove(r);
            break;
        }

        for (var fp : yearFileparts) {
            if (consumedYearFileparts.contains(fp)) continue;
            var titles = checkTitlesInFilepart(ctx, fp, m -> false);
            if (titles == null) continue;
            for (var t : titles.titles) ctx.matches.add(t);
            for (var r : titles.toRemove) ctx.matches.remove(r);
        }
    }

    private void preferTitleWithYear(ParseContext ctx) {
        var titles = ctx.matches.named("title").toList();
        if (titles.isEmpty()) return;
        var withYearInGroup = new ArrayList<Match>();
        var withYear = new ArrayList<Match>();
        for (var t : titles) {
            var fp = Markers.atMatch(ctx.markers, t, m -> "path".equals(m.name())).orElse(null);
            if (fp == null) continue;
            var year = ctx.matches.range(fp.start(), fp.end(), m -> "year".equals(m.name())).findFirst().orElse(null);
            if (year == null) continue;
            var inGroup = Markers.atMatch(ctx.markers, year, m -> "group".equals(m.name())).isPresent();
            (inGroup ? withYearInGroup : withYear).add(t);
        }
        Set<Object> keepValues;
        if (!withYearInGroup.isEmpty()) keepValues = withYearInGroup.stream().map(Match::value).collect(java.util.stream.Collectors.toSet());
        else if (!withYear.isEmpty()) keepValues = withYear.stream().map(Match::value).collect(java.util.stream.Collectors.toSet());
        else return;
        for (var t : titles) if (!keepValues.contains(t.value())) ctx.matches.remove(t);
    }

    private boolean serieNameIgnored(Match m) {
        for (var tag : m.tags()) {
            if ("weak".equals(tag) || tag.startsWith("weak-")) return true;
        }
        return false;
    }

    private Marker serieNameFilepart(ParseContext ctx, List<Marker> fileparts) {
        for (var index = 1; index < fileparts.size() - 1; index++) {
            var fp = fileparts.get(index);
            var inFp = ctx.matches.range(fp.start(), fp.end(), m -> !m.isPrivate()).toList();
            if (inFp.size() == 1 && "season".equals(inFp.get(0).name())
                    && inFp.get(0).start() == fp.start() && inFp.get(0).end() == fp.end()) {
                return fileparts.get(index + 1);
            }
        }
        return null;
    }

    record TitlesInFilepart(List<Match> titles, List<Match> toRemove) {}

    /** Returns null when no usable hole was found. */
    TitlesInFilepart checkTitlesInFilepart(ParseContext ctx, Marker filepart,
                                            java.util.function.Predicate<Match> additionalIgnore) {
        var ignore = (java.util.function.Predicate<Match>) m ->
            isIgnored(m) || (additionalIgnore != null && additionalIgnore.test(m));
        return checkTitlesInFilepart(ctx, filepart, ignore, "title", List.of("title"), "alternative_title", false);
    }

    /**
     * Shared implementation used by EpisodeTitleExtractor too; emits {@code matchName}-named
     * matches and (when {@code alternativeMatchName != null}) splits the hole on title_seps
     * to spawn alternative-title matches.
     */
    TitlesInFilepart checkTitlesInFilepart(ParseContext ctx, Marker filepart,
                                            java.util.function.Predicate<Match> ignore,
                                            String matchName, List<String> matchTags,
                                            String alternativeMatchName,
                                            boolean episodeTitleContext) {
        var allMatches = ctx.matches.snapshot();
        var holes = Holes.compute(ctx.input, filepart.start(), filepart.end() + 1,
            allMatches, ignore, null, Formatters::titleText);
        holes = holesProcess(ctx, holes);

        for (var hole : holes) {
            if (hole == null) continue;
            var toRemove = new ArrayList<Match>();
            var toKeep = new ArrayList<Match>();
            var ignoredInHole = ctx.matches.range(hole.start, hole.end, this::isIgnored).toList();
            if (!ignoredInHole.isEmpty()) {
                var reversed = new ArrayList<>(ignoredInHole);
                java.util.Collections.reverse(reversed);
                for (var m : reversed) {
                    var trailing = ctx.matches.chainBefore(hole.end, ctx.input, Seps.CHARS, x -> x == m).orElse(null);
                    if (trailing != null && shouldKeep(m, toKeep, ctx, filepart, hole, false)) {
                        toKeep.add(m);
                        hole.end = m.start();
                    }
                }
                for (var m : ignoredInHole) {
                    if (toKeep.contains(m)) continue;
                    var starting = ctx.matches.chainAfter(hole.start, ctx.input, Seps.CHARS, x -> x == m).orElse(null);
                    if (starting != null && shouldKeep(m, toKeep, ctx, filepart, hole, true)) {
                        toKeep.add(m);
                        hole.start = m.end();
                    }
                }
            }
            for (var m : ignoredInHole) {
                if (shouldRemove(m, ctx, hole, episodeTitleContext)) toRemove.add(m);
            }
            toRemove.removeAll(toKeep);

            if (hole.length() <= 0 || hole.value().isEmpty()) continue;

            var titles = new ArrayList<Match>();
            var raw = hole.raw();
            var value = hole.value();
            titles.add(new Match(matchName, value, hole.start, hole.end, raw, 1000, Set.copyOf(matchTags), false));

            if (alternativeMatchName != null) {
                var split = hole.split(Seps.TITLE_CHARS);
                if (split.size() > 1) {
                    titles.clear();
                    titles.add(new Match(matchName, split.get(0).value(), split.get(0).start, split.get(0).end,
                        split.get(0).raw(), 1000, Set.copyOf(matchTags), false));
                    for (var i = 1; i < split.size(); i++) {
                        var s = split.get(i);
                        titles.add(new Match(alternativeMatchName, s.value(), s.start, s.end, s.raw(),
                            1000, Set.of("title"), false));
                    }
                }
            }
            return new TitlesInFilepart(titles, toRemove);
        }
        return null;
    }

    private List<Holes.Hole> holesProcess(ParseContext ctx, List<Holes.Hole> holes) {
        var groupMarkers = new ArrayList<>(Markers.named(ctx.markers, "group").toList());
        var iter = groupMarkers.iterator();
        while (iter.hasNext()) {
            var g = iter.next();
            var path = Markers.atMatch(ctx.markers, new Match("g", null, g.start(), g.end(), g.raw()),
                m -> "path".equals(m.name())).orElse(null);
            if (path != null && path.start() == g.start() && path.end() == g.end()) iter.remove();
        }
        var ret = new ArrayList<Holes.Hole>();
        for (var h : holes) ret.addAll(h.crop(groupMarkers));
        return ret;
    }

    static boolean isIgnored(Match m) {
        if (!Set.of("language", "country", "episode_details").contains(m.name())) return false;
        var raw = m.raw();
        if (raw == null) return true;
        var upper = raw.equals(raw.toUpperCase(java.util.Locale.ROOT))
            && raw.chars().anyMatch(Character::isLetter);
        return !(raw.length() > 3 && upper);
    }

    private boolean shouldKeep(Match m, List<Match> toKeep, ParseContext ctx, Marker filepart,
                               Holes.Hole hole, boolean starting) {
        if (Set.of("language", "country").contains(m.name())) {
            if (hole.value().length() == m.raw().length()) return true;
            var others = ctx.matches.range(filepart.start(), filepart.end(),
                x -> x.name().equals(m.name()) && !toKeep.contains(x)
                    && !NON_SPECIFIC_LANGUAGES.contains(String.valueOf(x.value()))
                    && (x.end() <= hole.start || x.start() >= hole.end));
            if (others.findAny().isEmpty() && (!starting || m.raw().length() <= 3)) return true;
        }
        return false;
    }

    private boolean shouldRemove(Match m, ParseContext ctx, Holes.Hole hole, boolean episodeTitleContext) {
        if ("episode_details".equals(m.name())) {
            if (episodeTitleContext) return false;
            if ("episode".equals(ctx.options.type())) {
                return m.start() >= hole.start && m.end() <= hole.end;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=TitleExtractorTest`
Expected: FAIL on the first test (TitleExtractor isn't yet wired into Rules). Skip running until Task 9.

- [ ] **Step 5: Commit (tests will pass once wired in Task 9)**

```bash
git add src/main/java/io/guessit/rules/property/TitleExtractor.java src/test/java/io/guessit/rules/property/TitleExtractorTest.java
git commit -m "feat(rules): add TitleExtractor (expected_title + TitleFromPosition + PreferTitleWithYear)"
```

---

## Task 8: `rules/property/EpisodeTitleExtractor` — six episode_title sub-rules

**Files:**
- Create: `src/main/java/io/guessit/rules/property/EpisodeTitleExtractor.java`
- Test: `src/test/java/io/guessit/rules/property/EpisodeTitleExtractorTest.java`

The extractor's `postProcess` runs Python's six rules in order:

1. `RemoveConflictsWithEpisodeTitle` — drop `part`/`year` matches inside a path filepart when they're between a previous-name match (`episode|episode_count|season|season_count|date|title|year`) and a next-name match (`streaming_service|screen_size|source|video_codec|audio_codec|other|container`), with same-group hole(s) on at least one side. For `part`, only when there's a hole *after*.
2. `TitleToEpisodeTitle` — when ≥2 distinct `title` values exist, rename any `title` whose immediate `previous` is `episode` to `episode_title`.
3. `EpisodeTitleFromPosition` — like `TitleFromPosition` but `match_name = "episode_title"`, no alternative-title split. Skips if any `episode_title` exists. `hole_filter`: keep only holes whose previous-non-private match is in `previous_names` or any `crc32` exists. `filepart_filter`: filepart must contain a `title`. `should_remove`: do NOT remove `episode_details`.
4. `AlternativeTitleReplace` — if no `episode_title` exists and an `alternative_title` exists with a chain-before `title` whose previous match is in `previous_names` (or `crc32` exists), rename the alternative to `episode_title` and tag `alternative-replaced`.
5. `Filepart3EpisodeTitle` — when ≥3 path markers, last contains `episode`, 2nd-last contains `season`, take cleaned hole in 3rd-last (ignore `weak-episode`-tagged + `is_ignored`, seps=`title_seps`) as a `title`. Skip if any match tagged `filepart-title`.
6. `Filepart2EpisodeTitle` — when ≥2 path markers, last contains `episode`, last or 2nd-last contains `season`, take cleaned hole in 2nd-last as a `title`, tag `filepart-title`. Skip if already tagged.

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodeTitleExtractorTest {
    @Test void episodeTitleFromPositionFillsHoleAfterEpisode() {
        var r = Guessit.parse("Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat(r.get("episode_title")).isEqualTo("Episode Title");
    }
    @Test void titleToEpisodeTitleRenamesSecondTitleAfterEpisode() {
        var r = Guessit.parse("Foo/Show.Name.S01E02.Episode.Title.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat(r.get("episode_title")).isEqualTo("Episode Title");
    }
    @Test void filepart3() {
        var r = Guessit.parse("Series Name/Season 1/E01-episode-title.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Series Name");
        assertThat(r.get("episode")).isEqualTo(1);
    }
    @Test void filepart2() {
        var r = Guessit.parse("Series Name S01/E01-episode-title.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Series Name");
    }
    @Test void renameEpisodeTitleWhenMovieType() {
        var r = Guessit.parse("Movie.Name.2020.Bonus.Material.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Movie Name");
        assertThat(r.get("alternative_title")).isEqualTo("Bonus Material");
        assertThat(r.get("episode_title")).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EpisodeTitleExtractorTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EpisodeTitleExtractor implements Extractor {
    private static final Set<String> PREVIOUS_NAMES = Set.of(
        "episode", "episode_count", "season", "season_count", "date", "title", "year");
    private static final Set<String> NEXT_NAMES = Set.of(
        "streaming_service", "screen_size", "source", "video_codec",
        "audio_codec", "other", "container");
    private static final Set<String> AFFECTED_NAMES = Set.of("part", "year");
    private static final Set<String> AFFECTED_IF_HOLES_AFTER = Set.of("part");

    @Override
    public String name() { return "episode_title"; }

    @Override
    public void extract(ParseContext ctx) { /* no extraction phase */ }

    @Override
    public void postProcess(ParseContext ctx) {
        removeConflictsWithEpisodeTitle(ctx);
        titleToEpisodeTitle(ctx);
        episodeTitleFromPosition(ctx);
        alternativeTitleReplace(ctx);
        filepart3EpisodeTitle(ctx);
        filepart2EpisodeTitle(ctx);
    }

    private void removeConflictsWithEpisodeTitle(ParseContext ctx) {
        var toRemove = new ArrayList<Match>();
        for (var fp : Markers.named(ctx.markers, "path").toList()) {
            var inFp = ctx.matches.range(fp.start(), fp.end(), m -> AFFECTED_NAMES.contains(m.name())).toList();
            for (var m : inFp) {
                var before = ctx.matches.range(fp.start(), m.start(), x -> !x.isPrivate())
                    .reduce((a, b) -> b).orElse(null);
                if (before == null || !PREVIOUS_NAMES.contains(before.name())) continue;
                var after = ctx.matches.range(m.end(), fp.end(), x -> !x.isPrivate()).findFirst().orElse(null);
                if (after == null || !NEXT_NAMES.contains(after.name())) continue;
                var group = Markers.atMatch(ctx.markers, m, mk -> "group".equals(mk.name())).orElse(null);
                java.util.function.Predicate<Match> sameGroup = c ->
                    c.value() != null && !c.raw().strip().isEmpty()
                        && java.util.Objects.equals(group,
                            Markers.atMatch(ctx.markers, c, mk -> "group".equals(mk.name())).orElse(null));

                var holesBefore = Holes.compute(ctx.input, before.end(), m.start(),
                    ctx.matches.snapshot(), n -> false, null, Formatters::cleanup);
                var holesAfter = Holes.compute(ctx.input, m.end(), after.start(),
                    ctx.matches.snapshot(), n -> false, null, Formatters::cleanup);
                if (holesBefore.isEmpty() && holesAfter.isEmpty()) continue;
                if (AFFECTED_IF_HOLES_AFTER.contains(m.name()) && holesAfter.isEmpty()) continue;
                toRemove.add(m);
            }
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }

    private void titleToEpisodeTitle(ParseContext ctx) {
        var titles = ctx.matches.named("title").toList();
        var values = new java.util.HashSet<Object>();
        for (var t : titles) values.add(t.value());
        if (values.size() < 2) return;
        for (var t : titles) {
            var prev = ctx.matches.previous(t, m -> "episode".equals(m.name()));
            if (prev.isPresent()) {
                ctx.matches.replace(t, new Match("episode_title", t.value(), t.start(), t.end(),
                    t.raw(), t.priority(), t.tags(), t.isPrivate()));
            }
        }
    }

    private void episodeTitleFromPosition(ParseContext ctx) {
        if (ctx.matches.named("episode_title").findAny().isPresent()) return;
        var paths = ctx.markers.stream().filter(m -> "path".equals(m.name())).toList();
        var titleExtractor = new TitleExtractor();
        var hasCrc = ctx.matches.named("crc32").findAny().isPresent();
        for (var fp : Markers.markerSorted(paths, ctx.matches)) {
            var hasTitle = ctx.matches.range(fp.start(), fp.end(), m -> "title".equals(m.name())).findAny().isPresent();
            if (!hasTitle) continue;
            var titles = titleExtractor.checkTitlesInFilepart(ctx, fp,
                m -> false, "episode_title", List.of("title"), null, true);
            if (titles == null) continue;
            for (var t : titles.titles()) {
                var prev = ctx.matches.previous(t, m -> PREVIOUS_NAMES.contains(m.name()));
                if (prev.isPresent() || hasCrc) ctx.matches.add(t);
            }
            for (var r : titles.toRemove()) ctx.matches.remove(r);
        }
    }

    private void alternativeTitleReplace(ParseContext ctx) {
        if (ctx.matches.named("episode_title").findAny().isPresent()) return;
        var alt = ctx.matches.named("alternative_title").findFirst().orElse(null);
        if (alt == null) return;
        var mainTitle = ctx.matches.chainBefore(alt.start(), ctx.input, Seps.CHARS,
            m -> m.tags().contains("title")).orElse(null);
        if (mainTitle == null) return;
        var prev = ctx.matches.previous(mainTitle, m -> PREVIOUS_NAMES.contains(m.name()));
        var hasCrc = ctx.matches.named("crc32").findAny().isPresent();
        if (prev.isPresent() || hasCrc) {
            var newTags = new java.util.HashSet<>(alt.tags());
            newTags.add("alternative-replaced");
            ctx.matches.replace(alt, new Match("episode_title", alt.value(), alt.start(), alt.end(),
                alt.raw(), alt.priority(), Set.copyOf(newTags), alt.isPrivate()));
        }
    }

    private void filepart3EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged("filepart-title").findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 3) return;
        var filename = paths.get(paths.size() - 1);
        var directory = paths.get(paths.size() - 2);
        var subdirectory = paths.get(paths.size() - 3);
        if (ctx.matches.range(filename.start(), filename.end(), m -> "episode".equals(m.name())).findAny().isEmpty()) return;
        if (ctx.matches.range(directory.start(), directory.end(), m -> "season".equals(m.name())).findAny().isEmpty()) return;
        java.util.function.Predicate<Match> ignore = m -> m.tags().contains("weak-episode") || TitleExtractor.isIgnored(m);
        var holes = Holes.compute(ctx.input, subdirectory.start(), subdirectory.end(),
            ctx.matches.snapshot(), ignore, Seps.TITLE_CHARS, Formatters::cleanup);
        if (holes.isEmpty()) return;
        var h = holes.get(0);
        ctx.matches.add(new Match("title", h.value(), h.start, h.end, h.raw(), 1000, Set.of(), false));
    }

    private void filepart2EpisodeTitle(ParseContext ctx) {
        if (ctx.matches.tagged("filepart-title").findAny().isPresent()) return;
        var paths = Markers.named(ctx.markers, "path").toList();
        if (paths.size() < 2) return;
        var filename = paths.get(paths.size() - 1);
        var directory = paths.get(paths.size() - 2);
        if (ctx.matches.range(filename.start(), filename.end(), m -> "episode".equals(m.name())).findAny().isEmpty()) return;
        var hasSeason = ctx.matches.range(directory.start(), directory.end(), m -> "season".equals(m.name())).findAny().isPresent()
            || ctx.matches.range(filename.start(), filename.end(), m -> "season".equals(m.name())).findAny().isPresent();
        if (!hasSeason) return;
        java.util.function.Predicate<Match> ignore = m -> m.tags().contains("weak-episode") || TitleExtractor.isIgnored(m);
        var holes = Holes.compute(ctx.input, directory.start(), directory.end(),
            ctx.matches.snapshot(), ignore, Seps.TITLE_CHARS, Formatters::cleanup);
        if (holes.isEmpty()) return;
        var h = holes.get(0);
        var tags = Set.of("filepart-title");
        ctx.matches.add(new Match("title", h.value(), h.start, h.end, h.raw(), 1000, tags, false));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=EpisodeTitleExtractorTest`
Expected: FAIL until wired (Task 9).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/EpisodeTitleExtractor.java src/test/java/io/guessit/rules/property/EpisodeTitleExtractorTest.java
git commit -m "feat(rules): add EpisodeTitleExtractor (six chained episode_title rules)"
```

---

## Task 9: Wire Phase 4 into the pipeline

**Files:**
- Modify: `src/main/java/io/guessit/rules/Rules.java`
- Modify: `src/main/java/io/guessit/engine/ParseContext.java`
- Delete: `src/main/java/io/guessit/rules/post/TitleMarkerSelector.java`

- [ ] **Step 1: Update `Rules.java`**

In `Rules.allInOrder()`, append (after `ReleaseGroupExtractor`):

```java
new TitleExtractor(),
new EpisodeTitleExtractor()
```

In `Rules.defaultPipeline()`, replace the `PostPhase`'s processor list:

```java
new PostPhase(List.of(
    new PreferLastPath(),
    new TypeProcessor(),
    new PrivateRemover()
)),
```

(`TitleMarkerSelector` is dropped.)

- [ ] **Step 2: Drop `ParseContext.titleMarker`**

In `ParseContext.java`, remove the `public Marker titleMarker;` field and its Javadoc bullet. Verify no other file references it (`grep -rn "titleMarker" src/`).

- [ ] **Step 3: Delete `TitleMarkerSelector.java`**

```bash
git rm src/main/java/io/guessit/rules/post/TitleMarkerSelector.java
```

- [ ] **Step 4: Run all tests**

Run: `mvn -q test`
Expected: PASS — TitleExtractorTest and EpisodeTitleExtractorTest now go green; all prior tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/Rules.java src/main/java/io/guessit/engine/ParseContext.java
git commit -m "feat(rules): register Phase 4 extractors and replace TitleMarkerSelector with TypeProcessor"
```

---

## Task 10: Widen YML parity gate

**Files:**
- Modify: `src/test/java/io/guessit/parity/YmlParityTest.java`

- [ ] **Step 1: Add Phase 4 keys to `PHASE_PROPS`**

```java
// Phase 4
"title", "alternative_title", "episode_title", "type"
```

- [ ] **Step 2: Run parity suite and read summary**

Run: `mvn -q test -Dtest=YmlParityTest`
Expected: ≥95% of cases passing within the widened gate. Investigate top 3 failure clusters; fix or document as Phase-5 deferred.

- [ ] **Step 3: Run full suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/guessit/parity/YmlParityTest.java
git commit -m "test: widen YML parity gate to Phase 4 properties"
```

---

## Verification

End-to-end:

1. `mvn -q -DskipTests package` — produces `target/guessit-java-<ver>.jar` and `-cli.jar`.
2. `mvn -q test` — entire suite green.
3. CLI smoke:
   ```
   java -jar target/guessit-java-*-cli.jar "Show.Name.S01E02.Episode.Title.720p.HDTV.x264-RG.mkv"
   ```
   Expected fields: `title=Show Name`, `season=1`, `episode=2`, `episode_title=Episode Title`, `screen_size=720p`, `source=HDTV`, `video_codec=H.264`, `release_group=RG`, `container=mkv`, `type=episode`.
4. CLI smoke (movie):
   ```
   java -jar target/guessit-java-*-cli.jar "The.Matrix.1999.1080p.BluRay-WiKi.mkv"
   ```
   Expected: `title=The Matrix`, `year=1999`, `screen_size=1080p`, `source=Blu-ray`, `release_group=WiKi`, `container=mkv`, `type=movie`.

## Out of scope (deferred to Phase 5)

The following extractors are not implemented in Phase 4; YML cases mentioning them in their expected map remain outside `PHASE_PROPS` until Phase 5:

`film`, `film_title`, `bonus`, `bonus_title`, `cd`, `cd_count`, `part`, `edition`, `crc32`, `size`, `bit_rate`, `mimetype`, plus the long-tail Python `Processors` cleanup rules (other than `TypeProcessor`).
