# Verbose Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `-v` / `--verbose` flag on `GuessitCli` that prints a structured trace of the parsing pipeline to stdout, mirroring Python `guessit -v` in spirit using Java-native pipeline concepts.

**Architecture:** Add a `Trace` interface (default no-op) + `PrintTrace` implementation. Each `Phase` snapshots `MatchSet` before/after its inner steps and emits diff events through a `Trace` sink stored on `ParseContext`. `Guessit` gets one new public overload `guess(String, Trace)`. CLI passes `PrintTrace(System.out)` when `-v` is set.

**Tech Stack:** Java 25, JUnit 5, AssertJ, picocli.

**Spec:** `docs/superpowers/specs/2026-05-05-verbose-mode-design.md`

**Conventions:**
- All assertions use AssertJ (`assertThat(...)`) — never JUnit `assertEquals/assertTrue/assertNotNull`.
- Test files mirror the package of the class under test, in `src/test/java`.
- Commit after each task. Conventional Commits style (`feat:`, `test:`, `refactor:`).

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/io/guessit/engine/Trace.java` | NEW | Sink interface; all default no-op methods. |
| `src/main/java/io/guessit/engine/PrintTrace.java` | NEW | `Trace` impl that formats events to an `Appendable`. |
| `src/main/java/io/guessit/engine/TraceDiff.java` | NEW | Package-private diff helper for `List<Match>` before/after snapshots. |
| `src/main/java/io/guessit/engine/ParseContext.java` | EDIT | Add public `Trace trace = Trace.NOOP` field. |
| `src/main/java/io/guessit/engine/MarkerPhase.java` | EDIT | Emit `phase("markers")` + per-marker `note(...)`. |
| `src/main/java/io/guessit/engine/ExtractorPhase.java` | EDIT | Emit `phase("extractors")`; per-extractor `step(...)` + diff. |
| `src/main/java/io/guessit/engine/ConflictPhase.java` | EDIT | Emit `phase("conflicts")` + whole-phase diff. |
| `src/main/java/io/guessit/engine/ExtractorPostPhase.java` | EDIT | Emit `phase("extractor_post")`; per-extractor `step(...)` + diff. |
| `src/main/java/io/guessit/engine/PostPhase.java` | EDIT | Emit `phase("post")`; per-processor `step(...)` + diff. |
| `src/main/java/io/guessit/engine/OutputPhase.java` | EDIT | Emit `phase("output")`. |
| `src/main/java/io/guessit/Guessit.java` | EDIT | New `guess(String, Trace)` overload; existing `guess(String)` delegates. |
| `src/main/java/io/guessit/cli/GuessitCli.java` | EDIT | When `-v`, build `PrintTrace(System.out)`, suppress formatted output, warn on `--json`/`--yaml`/`-P` combo. |
| `src/test/java/io/guessit/engine/PrintTraceTest.java` | NEW | Unit tests for match formatting + event sequencing. |
| `src/test/java/io/guessit/engine/TraceDiffTest.java` | NEW | Unit tests for snapshot diff. |
| `src/test/java/io/guessit/cli/VerboseCliTest.java` | NEW | End-to-end: capture stdout from `-v` run; assert key lines. |

---

## Task 1: Trace interface (no-op contract)

**Files:**
- Create: `src/main/java/io/guessit/engine/Trace.java`

- [ ] **Step 1: Create the interface**

```java
package io.guessit.engine;

import io.guessit.GuessResult;

/**
 * Sink for verbose pipeline trace events. All methods default to no-ops so
 * phases can call into the trace unconditionally without paying a cost when
 * verbose mode is disabled. CLI {@code -v} attaches a {@link PrintTrace}.
 */
public interface Trace {
    Trace NOOP = new Trace() {};

    default void input(String s) {}
    default void phase(String name) {}
    default void step(String kind, String name) {}
    default void added(Match m) {}
    default void removed(Match m) {}
    default void note(String msg) {}
    default void result(GuessResult r) {}
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/Trace.java
git commit -m "feat(engine): add Trace interface with no-op default sink"
```

---

## Task 2: ParseContext.trace field

**Files:**
- Modify: `src/main/java/io/guessit/engine/ParseContext.java`

- [ ] **Step 1: Add field**

Add the field (near the other public fields, after `markers`):

```java
public Trace trace = Trace.NOOP;
```

The full field block becomes:

```java
public final MatchSet matches = new MatchSet();
public final List<Marker> markers = new ArrayList<>();
public Trace trace = Trace.NOOP;
public GuessResultBuilder resultBuilder = GuessResultBuilder.result();
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/ParseContext.java
git commit -m "feat(engine): expose Trace sink on ParseContext"
```

---

## Task 3: PrintTrace — match formatting

**Files:**
- Create: `src/main/java/io/guessit/engine/PrintTrace.java`
- Test: `src/test/java/io/guessit/engine/PrintTraceTest.java`

- [ ] **Step 1: Write the failing test (formatting only)**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrintTraceTest {

    @Test
    void formatsBareMatchValueStartEndName() {
        var m = Match.of("year", 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).isEqualTo("2020:(11,15)+name=year");
    }

    @Test
    void includesPrivateBeforeName() {
        var m = new Match("weak_episode", 2020, 11, 15, "2020", 1000, Set.of(), true);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("2020:(11,15)+private+name=weak_episode");
    }

    @Test
    void includesPriorityWhenNotDefault() {
        var m = Match.of("source", "Blu-ray", 22, 28, "Blu-ray").withPriority(2048);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("Blu-ray:(22,28)+name=source+priority=2048");
    }

    @Test
    void omitsPriorityAtDefault() {
        var m = Match.of("year", 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("priority=");
    }

    @Test
    void includesTagsInInsertionOrder() {
        var tags = new java.util.LinkedHashSet<String>();
        tags.add("weak-episode");
        tags.add("weak-duplicate");
        var m = Match.of("season", 20, 11, 13, "20").withTags(tags);
        assertThat(PrintTrace.formatMatch(m))
            .isEqualTo("20:(11,13)+name=season+tags=[weak-episode,weak-duplicate]");
    }

    @Test
    void omitsTagsWhenEmpty() {
        var m = Match.of("year", 2020, 11, 15, "2020");
        assertThat(PrintTrace.formatMatch(m)).doesNotContain("tags=");
    }
}
```

- [ ] **Step 2: Run test — verify it fails (no class)**

Run: `mvn -q -Dtest=PrintTraceTest test`
Expected: COMPILATION FAILURE — `PrintTrace` does not exist.

- [ ] **Step 3: Create PrintTrace with formatMatch only (skeleton for events)**

```java
package io.guessit.engine;

import io.guessit.GuessResult;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@link Trace} that writes formatted lines to an {@link Appendable}. Used by
 * the CLI when {@code -v} is set; not part of the documented library API.
 */
public final class PrintTrace implements Trace {

    private final Appendable out;

    public PrintTrace(Appendable out) { this.out = out; }

    static String formatMatch(Match m) {
        var sb = new StringBuilder();
        sb.append(m.raw()).append(':').append('(').append(m.start()).append(',').append(m.end()).append(')');
        if (m.isPrivate()) sb.append("+private");
        sb.append("+name=").append(m.name());
        if (m.priority() != 1000) sb.append("+priority=").append(m.priority());
        if (!m.tags().isEmpty()) {
            sb.append("+tags=[");
            boolean first = true;
            for (var t : m.tags()) {
                if (!first) sb.append(',');
                sb.append(t);
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @Override public void input(String s)    { write("For: " + s + "\n\n"); }
    @Override public void phase(String name) { write("[phase] " + name + "\n"); }
    @Override public void step(String kind, String name) { write("  [" + kind + "] " + name + "\n"); }
    @Override public void added(Match m)     { write("    + " + formatMatch(m) + "\n"); }
    @Override public void removed(Match m)   { write("    - " + formatMatch(m) + "\n"); }
    @Override public void note(String msg)   { write("  " + msg + "\n"); }
    @Override public void result(GuessResult r) {
        write("\nGuessIt found:\n" + io.guessit.cli.PlainFormatter.format(r) + "\n");
    }

    private void write(String s) {
        try { out.append(s); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `mvn -q -Dtest=PrintTraceTest test`
Expected: BUILD SUCCESS, all 6 cases green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/PrintTrace.java src/test/java/io/guessit/engine/PrintTraceTest.java
git commit -m "feat(engine): add PrintTrace with match formatter"
```

---

## Task 4: PrintTrace — event sequencing

**Files:**
- Modify: `src/test/java/io/guessit/engine/PrintTraceTest.java` (append cases)

- [ ] **Step 1: Add failing tests for line shape**

Append to `PrintTraceTest`:

```java
    @Test
    void inputLineFollowedByBlank() {
        var sb = new StringBuilder();
        new PrintTrace(sb).input("Movie.Name.2020.mkv");
        assertThat(sb.toString()).isEqualTo("For: Movie.Name.2020.mkv\n\n");
    }

    @Test
    void phaseLineHeader() {
        var sb = new StringBuilder();
        new PrintTrace(sb).phase("extractors");
        assertThat(sb.toString()).isEqualTo("[phase] extractors\n");
    }

    @Test
    void stepLineIndentedTwoSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).step("extract", "year");
        assertThat(sb.toString()).isEqualTo("  [extract] year\n");
    }

    @Test
    void addedLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        var m = Match.of("year", 2020, 11, 15, "2020");
        new PrintTrace(sb).added(m);
        assertThat(sb.toString()).isEqualTo("    + 2020:(11,15)+name=year\n");
    }

    @Test
    void removedLineIndentedFourSpaces() {
        var sb = new StringBuilder();
        var m = Match.of("year", 2020, 11, 15, "2020");
        new PrintTrace(sb).removed(m);
        assertThat(sb.toString()).isEqualTo("    - 2020:(11,15)+name=year\n");
    }

    @Test
    void noteLineIndentedTwoSpaces() {
        var sb = new StringBuilder();
        new PrintTrace(sb).note("marker: path:(0,41)+name=path");
        assertThat(sb.toString()).isEqualTo("  marker: path:(0,41)+name=path\n");
    }
```

- [ ] **Step 2: Run tests — verify they pass (already implemented in Task 3)**

Run: `mvn -q -Dtest=PrintTraceTest test`
Expected: BUILD SUCCESS, all 12 cases green.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/guessit/engine/PrintTraceTest.java
git commit -m "test(engine): assert PrintTrace line shapes for each event"
```

---

## Task 5: TraceDiff helper

**Files:**
- Create: `src/main/java/io/guessit/engine/TraceDiff.java`
- Test: `src/test/java/io/guessit/engine/TraceDiffTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDiffTest {

    static class CapturingTrace implements Trace {
        final List<String> events = new ArrayList<>();
        @Override public void added(Match m)   { events.add("+ " + PrintTrace.formatMatch(m)); }
        @Override public void removed(Match m) { events.add("- " + PrintTrace.formatMatch(m)); }
    }

    @Test
    void emitsAddedForMatchPresentOnlyInAfter() {
        var year = Match.of("year", 2020, 11, 15, "2020");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(), List.of(year), trace);
        assertThat(trace.events).containsExactly("+ 2020:(11,15)+name=year");
    }

    @Test
    void emitsRemovedForMatchPresentOnlyInBefore() {
        var year = Match.of("year", 2020, 11, 15, "2020");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(year), List.of(), trace);
        assertThat(trace.events).containsExactly("- 2020:(11,15)+name=year");
    }

    @Test
    void emitsNothingWhenIdentical() {
        var year = Match.of("year", 2020, 11, 15, "2020");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(year), List.of(year), trace);
        assertThat(trace.events).isEmpty();
    }

    @Test
    void emitsRemovesBeforeAdds() {
        var year = Match.of("year", 2020, 11, 15, "2020");
        var screen = Match.of("screen_size", "1080p", 16, 21, "1080p");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(year), List.of(screen), trace);
        assertThat(trace.events).containsExactly(
            "- 2020:(11,15)+name=year",
            "+ 1080p:(16,21)+name=screen_size"
        );
    }

    @Test
    void preservesAfterOrderForAdds() {
        var a = Match.of("year", 2020, 11, 15, "2020");
        var b = Match.of("screen_size", "1080p", 16, 21, "1080p");
        var trace = new CapturingTrace();
        TraceDiff.emit(List.of(), List.of(a, b), trace);
        assertThat(trace.events).containsExactly(
            "+ 2020:(11,15)+name=year",
            "+ 1080p:(16,21)+name=screen_size"
        );
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn -q -Dtest=TraceDiffTest test`
Expected: COMPILATION FAILURE — `TraceDiff` does not exist.

- [ ] **Step 3: Implement TraceDiff**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes added / removed matches between two {@link MatchSet} snapshots and
 * forwards them to a {@link Trace}. Multiset-aware: if the same value-equal
 * {@link Match} appears N times in {@code before} and M times in {@code after},
 * {@code max(0, N-M)} removals and {@code max(0, M-N)} additions are emitted.
 *
 * <p>Removals are emitted before additions; additions preserve {@code after}
 * order; removals preserve {@code before} order.
 */
final class TraceDiff {
    private TraceDiff() {}

    static void emit(List<Match> before, List<Match> after, Trace trace) {
        var afterCounts = new HashMap<Match, Integer>();
        for (var m : after) afterCounts.merge(m, 1, Integer::sum);

        var removals = new ArrayList<Match>();
        for (var m : before) {
            var c = afterCounts.getOrDefault(m, 0);
            if (c == 0) {
                removals.add(m);
            } else {
                afterCounts.put(m, c - 1);
            }
        }
        for (var m : removals) trace.removed(m);

        var beforeCounts = new HashMap<Match, Integer>();
        for (var m : before) beforeCounts.merge(m, 1, Integer::sum);
        for (var m : after) {
            var c = beforeCounts.getOrDefault(m, 0);
            if (c == 0) {
                trace.added(m);
            } else {
                beforeCounts.put(m, c - 1);
            }
        }
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `mvn -q -Dtest=TraceDiffTest test`
Expected: BUILD SUCCESS, all 5 cases green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/TraceDiff.java src/test/java/io/guessit/engine/TraceDiffTest.java
git commit -m "feat(engine): add TraceDiff helper for snapshot diffing"
```

---

## Task 6: Guessit.guess(String, Trace) overload

**Files:**
- Modify: `src/main/java/io/guessit/Guessit.java`

- [ ] **Step 1: Add the overload**

Replace the existing `guess` method block:

```java
    public GuessResult guess(String input) {
        return guess(input, Trace.NOOP);
    }

    public GuessResult guess(String input, Trace trace) {
        var ctx = new ParseContext(input, options, config);
        ctx.trace = trace;
        trace.input(input);
        pipeline.run(ctx);
        trace.result(ctx.result);
        return ctx.result;
    }
```

Add the missing import:

```java
import io.guessit.engine.Trace;
```

- [ ] **Step 2: Compile + run existing tests (no regressions)**

Run: `mvn -q test`
Expected: BUILD SUCCESS. The existing `guess(String)` callers still work because the no-op trace produces no observable side effect.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/Guessit.java
git commit -m "feat(api): add Guessit.guess(String, Trace) overload"
```

---

## Task 7: MarkerPhase emits header + per-marker note

**Files:**
- Modify: `src/main/java/io/guessit/engine/MarkerPhase.java`

- [ ] **Step 1: Read current MarkerPhase to know where to insert**

Run: `cat src/main/java/io/guessit/engine/MarkerPhase.java`

Note the structure: the phase iterates marker producers and adds `Marker` objects to `ctx.markers`.

- [ ] **Step 2: Update apply() — emit phase header + diff per producer**

Wrap the existing producer loop:

```java
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("markers");
        var beforeAll = List.copyOf(ctx.markers);
        for (var p : producers) {
            p.produce(ctx);
        }
        for (var m : ctx.markers) {
            if (!beforeAll.contains(m)) {
                ctx.trace.note("marker: " + m.raw() + ":(" + m.start() + "," + m.end() + ")+name=" + m.name());
            }
        }
    }
```

Add import if missing:

```java
import java.util.List;
```

- [ ] **Step 3: Run all tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS — verbose output not yet exercised; existing tests still pass because trace defaults to NOOP.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/guessit/engine/MarkerPhase.java
git commit -m "feat(engine): MarkerPhase emits trace events"
```

---

## Task 8: ExtractorPhase emits header + per-extractor diff

**Files:**
- Modify: `src/main/java/io/guessit/engine/ExtractorPhase.java`

- [ ] **Step 1: Update apply()**

Replace the body:

```java
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("extractors");
        for (var e : extractors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("extract", e.name());
            e.extract(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx.trace);
        }
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/ExtractorPhase.java
git commit -m "feat(engine): ExtractorPhase emits trace events"
```

---

## Task 9: ConflictPhase emits header + whole-phase diff

**Files:**
- Modify: `src/main/java/io/guessit/engine/ConflictPhase.java`

- [ ] **Step 1: Update apply()**

Replace the body:

```java
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("conflicts");
        var before = ctx.matches.snapshot();
        ConflictSolver.solve(ctx.matches);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx.trace);
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/ConflictPhase.java
git commit -m "feat(engine): ConflictPhase emits trace events"
```

---

## Task 10: ExtractorPostPhase emits header + per-extractor diff

**Files:**
- Modify: `src/main/java/io/guessit/engine/ExtractorPostPhase.java`

- [ ] **Step 1: Update apply()**

Replace the body:

```java
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("extractor_post");
        for (var e : extractors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("post", e.name());
            e.postProcess(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx.trace);
        }
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/ExtractorPostPhase.java
git commit -m "feat(engine): ExtractorPostPhase emits trace events"
```

---

## Task 11: PostPhase emits header + per-processor diff

**Files:**
- Modify: `src/main/java/io/guessit/engine/PostPhase.java`

- [ ] **Step 1: Update apply()**

Replace the body:

```java
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("post");
        for (var p : processors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("rule", p.getClass().getSimpleName());
            p.process(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx.trace);
        }
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/PostPhase.java
git commit -m "feat(engine): PostPhase emits trace events"
```

---

## Task 12: OutputPhase emits header

**Files:**
- Modify: `src/main/java/io/guessit/engine/OutputPhase.java`

- [ ] **Step 1: Update apply()**

Replace the body:

```java
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("output");
        assembler.accept(ctx);
    }
```

- [ ] **Step 2: Run tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS — existing parity counts unchanged because trace is NOOP.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/engine/OutputPhase.java
git commit -m "feat(engine): OutputPhase emits trace events"
```

---

## Task 13: CLI wires PrintTrace under -v

**Files:**
- Modify: `src/main/java/io/guessit/cli/GuessitCli.java`

- [ ] **Step 1: Update call() to branch on verbose**

Replace the per-filename loop and surrounding code in `call()`:

```java
    @Override
    public Integer call() {
        if (filenames.isEmpty()) {
            System.err.println("No input filename provided. See --help.");
            return 2;
        }
        var opts = OptionsBuilder.options()
            .type(type)
            .name(name)
            .expectedTitle(expectedTitles)
            .expectedGroup(expectedGroups)
            .excludes(excludes)
            .includes(includes)
            .allowedLanguages(allowedLanguages)
            .allowedCountries(allowedCountries)
            .dateYearFirst(dateYearFirst ? Boolean.TRUE : null)
            .dateDayFirst(dateDayFirst ? Boolean.TRUE : null)
            .episodePreferNumber(episodePreferNumber ? Boolean.TRUE : null)
            .configPaths(configs)
            .noUserConfig(noUserConfig)
            .noDefaultConfig(noDefaultConfig)
            .build();
        var guessit = Guessit.withOptions(opts);

        if (verbose) {
            if (json || yaml || showProperty != null) {
                System.err.println("warning: --json/--yaml/--show-property ignored when --verbose is set");
            }
            var trace = new io.guessit.engine.PrintTrace(System.out);
            for (int i = 0; i < filenames.size(); i++) {
                if (i > 0) System.out.println();
                guessit.guess(filenames.get(i), trace);
            }
            return 0;
        }

        for (var fn : filenames) {
            var result = guessit.guess(fn);
            String output;
            if (showProperty != null) {
                var v = result.toMap().get(showProperty);
                output = v == null ? "" : v.toString();
            } else if (json) {
                output = JsonFormatter.format(result);
            } else if (yaml) {
                output = YamlFormatter.format(result);
            } else {
                output = PlainFormatter.format(result);
            }
            System.out.println(output);
        }
        return 0;
    }
```

- [ ] **Step 2: Run existing CLI tests**

Run: `mvn -q -Dtest=GuessitCliTest test`
Expected: BUILD SUCCESS — non-verbose path unchanged.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/cli/GuessitCli.java
git commit -m "feat(cli): wire -v/--verbose to PrintTrace pipeline"
```

---

## Task 14: End-to-end CLI verbose test

**Files:**
- Create: `src/test/java/io/guessit/cli/VerboseCliTest.java`

- [ ] **Step 1: Write the test**

```java
package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VerboseCliTest {

    @Test
    void verboseOutputContainsAllPhasesAndKnownEvents() {
        var out = run("-v", "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");

        assertThat(out)
            .contains("For: Movie.Name.2020.1080p.BluRay.x264-GRP.mkv")
            .contains("[phase] markers")
            .contains("[phase] extractors")
            .contains("[phase] conflicts")
            .contains("[phase] extractor_post")
            .contains("[phase] post")
            .contains("[phase] output")
            .contains("GuessIt found:");
    }

    @Test
    void verboseShowsKnownExtractorStepAndAddedMatch() {
        var out = run("-v", "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");

        assertThat(out).contains("[extract] year");
        assertThat(out).contains("+ 2020:(11,15)+name=year");
        assertThat(out).contains("[extract] screen_size");
        assertThat(out).contains("+ 1080p:(16,21)+name=screen_size");
    }

    @Test
    void verboseSuppressesJsonOutputAndWarnsOnStderr() {
        var baos = new ByteArrayOutputStream();
        var berr = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(berr, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new GuessitCli()).execute("-v", "--json",
                "Movie.Name.2020.1080p.mkv");
            assertThat(code).isZero();
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }

        var stdout = baos.toString(StandardCharsets.UTF_8);
        var stderr = berr.toString(StandardCharsets.UTF_8);

        assertThat(stderr).contains("warning: --json/--yaml/--show-property ignored when --verbose is set");
        // No JSON object on stdout since verbose suppresses formatted output.
        assertThat(stdout).doesNotContain("\"title\":");
    }

    @Test
    void verboseTwoFilenamesAreSeparatedByBlankLine() {
        var out = run("-v",
            "Movie.A.2020.mkv",
            "Movie.B.2021.mkv");
        assertThat(out).contains("For: Movie.A.2020.mkv");
        assertThat(out).contains("For: Movie.B.2021.mkv");
        // Two GuessIt found blocks expected.
        assertThat(out.split("GuessIt found:", -1)).hasSize(3);
    }

    private String run(String... args) {
        var baos = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            new CommandLine(new GuessitCli()).execute(args);
        } finally {
            System.setOut(prev);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -Dtest=VerboseCliTest test`
Expected: BUILD SUCCESS, all 4 cases green.

If a `[extract] year` / `+ 2020:(11,15)+name=year` assertion fails because the actual extractor `name()` differs (e.g. lowercase variant), inspect output (`mvn -Dtest=VerboseCliTest test` without `-q`) and adjust the assertion to the real `Extractor.name()` returned by `YearExtractor`. Do **not** change the extractor.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/guessit/cli/VerboseCliTest.java
git commit -m "test(cli): end-to-end verbose mode trace output"
```

---

## Task 15: Full regression check

- [ ] **Step 1: Run the entire suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. YML parity counts unchanged (NOOP trace path is the only path the parity test runner exercises).

- [ ] **Step 2: Manual smoke test**

Run:

```
mvn -q package -DskipTests
java -jar target/guessit-java-*-cli.jar -v "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv"
```

Expected: trace block followed by `GuessIt found:` line and a `{...}` plain map. Every `[phase] X` header present.

- [ ] **Step 3: No commit if no changes**

Skip if working tree is clean.

---

## Notes for the implementer

- **Don't touch extractor / processor classes.** Trace events come from the Phase wrappers only. If a step seems to lack visible output it is correct — the phase still calls into it.
- **NOOP path must stay free.** Every method on `Trace` has a default no-op body; do not add allocations to phases that occur even when verbose is off (the snapshot calls in phases are cheap copies of an `ArrayList<Match>` and acceptable for the parity test runs that already create tens of thousands of these).
- **Match equality.** `Match` is a record; equality is value-based. Diff is multiset-aware so identical-value matches added twice register as two adds.
- **PostProcessor names.** Every current processor in `Rules.defaultPipeline()` is a dedicated class; `getClass().getSimpleName()` gives a clean name. If a future change inlines a processor as a lambda, the trace will show a synthetic name; promote the lambda to a class instead of working around it.
- **Don't reset `.claude/settings.local.json`** when committing.
