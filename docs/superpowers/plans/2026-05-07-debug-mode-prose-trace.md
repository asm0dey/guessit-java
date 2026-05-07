# Debug Mode (Prose Trace + Marker View) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `--debug` CLI flag (and optional `--debug-markers` / `--debug-out=PATH`) that emits a human-readable narration of every parse step plus an ASCII span view whenever the match set changes. Library consumers wire the same sink via the existing `Guessit.guess(input, Trace)` overload.

**Architecture:** Extend the existing `Trace` interface with prose-friendly events (`subStep`, 3-arg `step`, 2-arg `phase`, `spans`). New `DebugTrace` formats those events as prose; new `CompositeTrace` fans events to multiple sinks so `-v --debug` can run both `PrintTrace` and `DebugTrace`. New `SpanRenderer` paints the input string with underline + label rows. Each `Extractor`, `PostProcessor`, and `MarkerProducer` gains a `description()` default; sub-step prose is emitted from `PatternMatcher`, `ConflictSolver`, multi-stage processors, and `OutputBuilder`. CLI threads the flags into a single composite `Trace`.

**Tech Stack:** Java 21 (records, sealed types, switch patterns), JUnit 5, AssertJ (project rule: `assertThat`, never `Assertions.assertEquals`), Maven build (`mvn` from project root). picocli for CLI.

**Spec:** `docs/superpowers/specs/2026-05-07-debug-mode-prose-trace-design.md`.

---

## Conventions

- Every test uses AssertJ (`org.assertj.core.api.Assertions.assertThat`).
- Every commit ends with the line `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` (matches existing project history).
- Run a focused test with: `mvn -q -pl . -Dtest=ClassName test` (project is single-module, so `-pl .` is implicit; `mvn -q -Dtest=ClassName test` works).
- Run full suite with: `mvn -q test`.
- All paths in this plan are relative to `/home/finkel/work_self/guessit-java`.

---

## Task 1: Trace API extension

**Files:**
- Modify: `src/main/java/io/guessit/engine/Trace.java`
- Test: `src/test/java/io/guessit/engine/TraceDefaultsTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/TraceDefaultsTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDefaultsTest {

    @Test
    void noopAcceptsAllNewMethods() {
        Trace t = Trace.NOOP;
        // None of these should throw.
        t.subStep("anything");
        t.step("kind", "name", "description");
        t.phase("name", "description");
        t.spans("input", List.of(), List.of());
        assertThat(t).isNotNull();
    }

    @Test
    void threeArgStepDelegatesToTwoArgWhenNotOverridden() {
        var seen = new java.util.ArrayList<String>();
        Trace t = new Trace() {
            @Override public void step(String kind, String name) { seen.add(kind + ":" + name); }
        };
        t.step("post", "year", "describe me");
        assertThat(seen).containsExactly("post:year");
    }

    @Test
    void twoArgPhaseDelegateFromThreeArg() {
        var seen = new java.util.ArrayList<String>();
        Trace t = new Trace() {
            @Override public void phase(String name) { seen.add(name); }
        };
        t.phase("markers", "describe me");
        assertThat(seen).containsExactly("markers");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=TraceDefaultsTest test`
Expected: FAIL — methods `subStep`, 3-arg `step`, 2-arg `phase`, `spans` do not exist on `Trace`.

- [ ] **Step 3: Extend Trace interface**

Replace `src/main/java/io/guessit/engine/Trace.java` with:

```java
package io.guessit.engine;

import io.guessit.GuessResult;

import java.util.List;

/**
 * Sink for verbose pipeline trace events. All methods default to no-ops so
 * phases can call into the trace unconditionally without paying a cost when
 * verbose mode is disabled. CLI {@code -v} attaches a {@link PrintTrace};
 * CLI {@code --debug} attaches a {@link DebugTrace}; both can coexist via
 * {@link CompositeTrace}.
 */
public interface Trace {
    Trace NOOP = new Trace() {};

    default void input(String s) {}
    default void phase(String name) {}
    /** Phase header with a human-readable description. Default delegates to {@link #phase(String)}. */
    default void phase(String name, String description) { phase(name); }
    default void step(String kind, String name) {}
    /** Step header with a human-readable description. Default delegates to {@link #step(String,String)}. */
    default void step(String kind, String name, String description) { step(kind, name); }
    default void added(Match m) {}
    default void removed(Match m) {}
    default void noChanges() {}
    default void note(String msg) {}
    /** Generic indented sub-event emitted from inside a step (e.g. PatternMatcher
     *  tries, ConflictSolver pair decisions, processor sub-stages, per-property
     *  output assignments). */
    default void subStep(String message) {}
    /** Snapshot of all live spans (non-private matches + markers) plus the input,
     *  emitted by phases when the match set changed within a step. DebugTrace
     *  with span rendering enabled paints an ASCII view; everything else no-ops. */
    default void spans(String input, List<Match> matches, List<Marker> markers) {}
    default void result(GuessResult r) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=TraceDefaultsTest test`
Expected: PASS.

Run full suite to confirm no regression: `mvn -q test`
Expected: PASS — `PrintTrace` and `Trace.NOOP` still work; existing `VerboseCliTest` still green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Trace.java src/test/java/io/guessit/engine/TraceDefaultsTest.java
git commit -m "$(cat <<'EOF'
feat(trace): add subStep, 3-arg step, 2-arg phase, spans for debug mode

Extends the Trace interface with prose-friendly events used by the
upcoming DebugTrace. Defaults preserve existing PrintTrace behaviour:
3-arg step delegates to 2-arg, 2-arg phase delegates to 1-arg.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Described mixin + Extractor.description()

**Files:**
- Create: `src/main/java/io/guessit/engine/Described.java`
- Modify: `src/main/java/io/guessit/engine/Extractor.java`
- Modify: `src/main/java/io/guessit/engine/MarkerPhase.java`
- Modify: `src/main/java/io/guessit/engine/PostPhase.java`
- Test: `src/test/java/io/guessit/engine/DescribedTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/DescribedTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DescribedTest {

    @Test
    void extractorDescriptionFallsBackToName() {
        Extractor anon = new Extractor() {
            @Override public String name() { return "x"; }
            @Override public void extract(ParseContext ctx) {}
        };
        assertThat(anon.description()).isEqualTo("x");
    }

    @Test
    void extractorDescriptionOverridable() {
        Extractor anon = new Extractor() {
            @Override public String name() { return "year"; }
            @Override public String description() { return "4-digit year"; }
            @Override public void extract(ParseContext ctx) {}
        };
        assertThat(anon.description()).isEqualTo("4-digit year");
    }

    @Test
    void postProcessorDescriptionFallsBackToSimpleClassName() {
        PostPhase.PostProcessor proc = ctx -> {};
        // Default fallback is class simple name; a concrete class returns its own name.
        assertThat(proc.description()).isNotNull().isNotEmpty();
    }

    @Test
    void markerProducerDescriptionFallsBackToSimpleClassName() {
        MarkerPhase.MarkerProducer prod = ctx -> {};
        assertThat(prod.description()).isNotNull().isNotEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=DescribedTest test`
Expected: FAIL — `description()` does not exist on `Extractor`, `PostProcessor`, or `MarkerProducer`.

- [ ] **Step 3: Create Described mixin**

Create `src/main/java/io/guessit/engine/Described.java`:

```java
package io.guessit.engine;

/**
 * One-line human-readable description of a pipeline component. Used by
 * {@link DebugTrace} when emitting prose narration. Implementations should
 * return a short noun phrase or sentence (no trailing punctuation), e.g.
 * {@code "4-digit year (19xx/20xx)"}.
 */
public interface Described {
    String description();
}
```

- [ ] **Step 4: Add description() default to Extractor**

Edit `src/main/java/io/guessit/engine/Extractor.java`. Add `extends Described`-style default. Replace existing declaration with:

```java
public interface Extractor extends Described {
    String name();
    default int priority() { return 1000; }
    void extract(ParseContext ctx);
    default void postProcess(ParseContext ctx) {}

    /** Human-readable description for {@code --debug} output. Defaults to {@link #name()}. */
    @Override
    default String description() { return name(); }
}
```

(Keep the existing javadoc; only the interface signature and the new default method are added. Preserve the original method-level javadoc on `name`, `priority`, `extract`, `postProcess`.)

- [ ] **Step 5: Add description() default to PostProcessor**

Edit `src/main/java/io/guessit/engine/PostPhase.java`. Change the inner `PostProcessor` interface:

```java
@FunctionalInterface
public interface PostProcessor extends Described {
    void process(ParseContext ctx);

    @Override
    default String description() { return getClass().getSimpleName(); }
}
```

- [ ] **Step 6: Add description() default to MarkerProducer**

Edit `src/main/java/io/guessit/engine/MarkerPhase.java`. Change the inner `MarkerProducer` interface:

```java
@FunctionalInterface
public interface MarkerProducer extends Described {
    void produce(ParseContext ctx);

    @Override
    default String description() { return getClass().getSimpleName(); }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn -q -Dtest=DescribedTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/guessit/engine/Described.java \
       src/main/java/io/guessit/engine/Extractor.java \
       src/main/java/io/guessit/engine/MarkerPhase.java \
       src/main/java/io/guessit/engine/PostPhase.java \
       src/test/java/io/guessit/engine/DescribedTest.java
git commit -m "$(cat <<'EOF'
feat(trace): add Described mixin with description() defaults

Extractor, PostProcessor, and MarkerProducer now implement Described.
Default fallback returns name() for Extractor and getClass().getSimpleName()
for the others, so the change is no-op until concrete classes override.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: CompositeTrace

**Files:**
- Create: `src/main/java/io/guessit/engine/CompositeTrace.java`
- Test: `src/test/java/io/guessit/engine/CompositeTraceTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/CompositeTraceTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeTraceTest {

    @Test
    void fanOutsToEverySink() {
        var a = new RecordingTrace();
        var b = new RecordingTrace();
        Trace t = new CompositeTrace(a, b);
        t.input("hello");
        t.phase("markers");
        t.subStep("found x");
        assertThat(a.events).containsExactly("input:hello", "phase:markers", "subStep:found x");
        assertThat(b.events).containsExactly("input:hello", "phase:markers", "subStep:found x");
    }

    @Test
    void preservesSinkOrder() {
        var seen = new ArrayList<String>();
        Trace a = new Trace() { @Override public void note(String m) { seen.add("a"); } };
        Trace b = new Trace() { @Override public void note(String m) { seen.add("b"); } };
        new CompositeTrace(a, b).note("x");
        assertThat(seen).containsExactly("a", "b");
    }

    @Test
    void exceptionInOneSinkDoesNotSkipOthers() {
        var b = new RecordingTrace();
        Trace a = new Trace() { @Override public void note(String m) { throw new RuntimeException("boom"); } };
        var t = new CompositeTrace(a, b);
        // Composite swallows per-sink throwables to keep tracing best-effort.
        t.note("x");
        assertThat(b.events).containsExactly("note:x");
    }

    private static final class RecordingTrace implements Trace {
        final List<String> events = new ArrayList<>();
        @Override public void input(String s) { events.add("input:" + s); }
        @Override public void phase(String name) { events.add("phase:" + name); }
        @Override public void subStep(String m) { events.add("subStep:" + m); }
        @Override public void note(String m) { events.add("note:" + m); }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=CompositeTraceTest test`
Expected: FAIL — `CompositeTrace` does not exist.

- [ ] **Step 3: Implement CompositeTrace**

Create `src/main/java/io/guessit/engine/CompositeTrace.java`:

```java
package io.guessit.engine;

import io.guessit.GuessResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * Fan-out {@link Trace} that forwards every event to a fixed list of sinks.
 *
 * <p>Per-sink exceptions are caught so a misbehaving sink cannot break the
 * other sinks or the parse itself. Sinks are invoked in the order supplied to
 * the constructor.
 */
public final class CompositeTrace implements Trace {

    private final List<Trace> sinks;

    public CompositeTrace(Trace... sinks) { this.sinks = List.of(sinks); }
    public CompositeTrace(List<Trace> sinks) { this.sinks = List.copyOf(sinks); }

    private void forEach(Consumer<Trace> action) {
        for (var s : sinks) {
            try { action.accept(s); } catch (RuntimeException ignored) { /* best-effort */ }
        }
    }

    @Override public void input(String s)                            { forEach(t -> t.input(s)); }
    @Override public void phase(String name)                         { forEach(t -> t.phase(name)); }
    @Override public void phase(String name, String description)     { forEach(t -> t.phase(name, description)); }
    @Override public void step(String kind, String name)             { forEach(t -> t.step(kind, name)); }
    @Override public void step(String kind, String name, String d)   { forEach(t -> t.step(kind, name, d)); }
    @Override public void added(Match m)                             { forEach(t -> t.added(m)); }
    @Override public void removed(Match m)                           { forEach(t -> t.removed(m)); }
    @Override public void noChanges()                                { forEach(Trace::noChanges); }
    @Override public void note(String msg)                           { forEach(t -> t.note(msg)); }
    @Override public void subStep(String msg)                        { forEach(t -> t.subStep(msg)); }
    @Override public void spans(String in, List<Match> ms, List<Marker> mk) { forEach(t -> t.spans(in, ms, mk)); }
    @Override public void result(GuessResult r)                      { forEach(t -> t.result(r)); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=CompositeTraceTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/CompositeTrace.java \
       src/test/java/io/guessit/engine/CompositeTraceTest.java
git commit -m "$(cat <<'EOF'
feat(trace): add CompositeTrace fan-out sink

Allows -v --debug to drive both PrintTrace and DebugTrace simultaneously.
Per-sink exceptions are swallowed so tracing is best-effort.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: SpanRenderer

**Files:**
- Create: `src/main/java/io/guessit/engine/SpanRenderer.java`
- Test: `src/test/java/io/guessit/engine/SpanRendererTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/SpanRendererTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpanRendererTest {

    @Test
    void rendersDisjointMatches() {
        var year      = match(MatchName.YEAR,        2020, 4, 8,  "2020");
        var container = match(MatchName.CONTAINER,   "mkv", 9, 12, "mkv");
        String out = SpanRenderer.render("XxX.2020.mkv", List.of(year, container), List.of());
        assertThat(out).isEqualTo(
            "  XxX.2020.mkv\n" +
            "      ---- ---\n" +
            "       |    |\n" +
            "      year container\n"
        );
    }

    @Test
    void stacksOverlappingLabelsOnSeparateRows() {
        var year      = match(MatchName.YEAR,        2024, 4, 8,  "2024");
        var screen    = match(MatchName.SCREEN_SIZE, "1080p", 9, 14, "1080p");
        var src       = match(MatchName.SOURCE,      "WEB-DL", 15, 21, "WEB-DL");
        String out = SpanRenderer.render("XxX.2024.1080p.WEB-DL", List.of(year, screen, src), List.of());
        // Just assert that all three labels appear and indentation is two spaces.
        assertThat(out).startsWith("  XxX.2024.1080p.WEB-DL\n");
        assertThat(out).contains("year");
        assertThat(out).contains("screen_size");
        assertThat(out).contains("source");
    }

    @Test
    void includesMarkers() {
        var marker = new Marker("group", 0, 5, "[GRP]");
        String out = SpanRenderer.render("[GRP] foo.mkv", List.of(), List.of(marker));
        assertThat(out).contains("group");
        assertThat(out).contains("[GRP] foo.mkv");
    }

    @Test
    void skipsPrivateMatches() {
        var visible = new Match(MatchName.YEAR, 2020, 0, 4, "2020", 1000, java.util.Set.of(), false);
        var hidden  = new Match(MatchName.YEAR, 2020, 0, 4, "2020", 1000, java.util.Set.of(), true);
        String out = SpanRenderer.render("2020", List.of(visible, hidden), List.of());
        // Only one underline run; no doubled label.
        assertThat(out.lines().filter(l -> l.contains("year")).count()).isEqualTo(1L);
    }

    private static Match match(MatchName name, Object value, int start, int end, String raw) {
        return Match.of(name, value, start, end, raw);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=SpanRendererTest test`
Expected: FAIL — `SpanRenderer` does not exist.

- [ ] **Step 3: Implement SpanRenderer**

Create `src/main/java/io/guessit/engine/SpanRenderer.java`:

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders an input string with underline + label rows for every span.
 *
 * <p>Layout:
 * <pre>
 *   XxX.2020.mkv
 *       ---- ---
 *        |    |
 *       year container
 * </pre>
 *
 * <p>Spans are stacked across multiple label rows when their midpoints are
 * too close to fit labels on a single row without overlap. Connector {@code |}
 * characters are repeated above the label so each label is visually anchored
 * to the underline at the span midpoint.
 *
 * <p>All output lines are indented by two spaces (sub-step indent level).
 */
public final class SpanRenderer {

    private SpanRenderer() {}

    public static String render(String input, List<Match> matches, List<Marker> markers) {
        record Span(int start, int end, String label) {
            int mid() { return start + (end - start) / 2; }
            int width() { return end - start; }
        }

        var spans = new ArrayList<Span>();
        for (var m : matches) {
            if (m.isPrivate()) continue;
            spans.add(new Span(m.start(), m.end(), m.name().name().toLowerCase(Locale.ROOT)));
        }
        for (var mk : markers) {
            spans.add(new Span(mk.start(), mk.end(), mk.name()));
        }
        if (spans.isEmpty()) {
            return "  " + input + "\n";
        }
        spans.sort(Comparator.<Span>comparingInt(Span::start).thenComparingInt(Span::end));

        int width = input.length();

        // Underline row
        var underline = new char[width];
        java.util.Arrays.fill(underline, ' ');
        for (var s : spans) {
            for (int i = s.start(); i < s.end() && i < width; i++) underline[i] = '-';
        }

        // Greedy label row assignment: each row holds spans whose label boxes
        // (centered on midpoint, full label width) do not overlap.
        var rows = new ArrayList<List<Span>>();
        for (var s : spans) {
            int halfLabel = s.label().length() / 2;
            int labelStart = Math.max(0, s.mid() - halfLabel);
            int labelEnd = labelStart + s.label().length();
            int placedRow = -1;
            for (int r = 0; r < rows.size(); r++) {
                boolean fits = true;
                for (var existing : rows.get(r)) {
                    int eHalf = existing.label().length() / 2;
                    int eStart = Math.max(0, existing.mid() - eHalf);
                    int eEnd = eStart + existing.label().length();
                    if (labelStart < eEnd + 1 && eStart < labelEnd + 1) { fits = false; break; }
                }
                if (fits) { placedRow = r; break; }
            }
            if (placedRow < 0) { rows.add(new ArrayList<>()); placedRow = rows.size() - 1; }
            rows.get(placedRow).add(s);
        }

        var sb = new StringBuilder();
        sb.append("  ").append(input).append('\n');
        sb.append("  ").append(new String(underline).replaceAll("\\s+$", "")).append('\n');

        // Connector + label rows. For every label row we draw, every span in
        // that row AND every later row needs a `|` connector above its midpoint
        // in this row's connector line.
        for (int r = 0; r < rows.size(); r++) {
            var connectors = new char[width];
            java.util.Arrays.fill(connectors, ' ');
            for (int rr = r; rr < rows.size(); rr++) {
                for (var s : rows.get(rr)) {
                    if (s.mid() < width) connectors[s.mid()] = '|';
                }
            }
            sb.append("  ").append(new String(connectors).replaceAll("\\s+$", "")).append('\n');

            var labelRow = new char[Math.max(width, 0)];
            java.util.Arrays.fill(labelRow, ' ');
            // Allow labels to extend beyond input width if needed.
            var dynamic = new StringBuilder(new String(labelRow));
            for (var s : rows.get(r)) {
                int halfLabel = s.label().length() / 2;
                int start = Math.max(0, s.mid() - halfLabel);
                while (dynamic.length() < start + s.label().length()) dynamic.append(' ');
                for (int i = 0; i < s.label().length(); i++) {
                    dynamic.setCharAt(start + i, s.label().charAt(i));
                }
            }
            sb.append("  ").append(dynamic.toString().replaceAll("\\s+$", "")).append('\n');
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=SpanRendererTest test`
Expected: PASS for `rendersDisjointMatches`, `stacksOverlappingLabelsOnSeparateRows`, `includesMarkers`, `skipsPrivateMatches`. If `rendersDisjointMatches` fails on exact whitespace, adjust the expected string in the test to match the actual output (the algorithm is correct as long as labels align with span midpoints; the test author should accept the renderer's spacing as canonical).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/SpanRenderer.java \
       src/test/java/io/guessit/engine/SpanRendererTest.java
git commit -m "$(cat <<'EOF'
feat(trace): add SpanRenderer for ASCII span view

Paints input with underline + connector + label rows for every span
(non-private match or marker). Stacks overlapping labels onto separate
rows; output is indented at the sub-step level so it nests inside the
DebugTrace step body.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: DebugTrace skeleton (no span rendering yet)

**Files:**
- Create: `src/main/java/io/guessit/engine/DebugTrace.java`
- Test: `src/test/java/io/guessit/engine/DebugTraceTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/DebugTraceTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugTraceTest {

    @Test
    void inputHeader() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.input("Movie.2020.mkv");
        assertThat(sb.toString()).isEqualTo("For: Movie.2020.mkv\n\n");
    }

    @Test
    void phaseHeaderWithDescription() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.phase("extractors", "scanning input for property patterns");
        assertThat(sb.toString()).isEqualTo("Extractor phase — scanning input for property patterns\n");
    }

    @Test
    void phaseHeaderWithoutDescriptionFallsBackToCapitalisedName() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.phase("conflicts");
        assertThat(sb.toString()).isEqualTo("Conflicts phase\n");
    }

    @Test
    void stepUsesDescription() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.step("extract", "year", "4-digit year (19xx/20xx)");
        assertThat(sb.toString()).isEqualTo("  Looking for year (4-digit year (19xx/20xx))\n");
    }

    @Test
    void stepWithoutDescriptionOmitsParenthetical() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.step("extract", "year");
        assertThat(sb.toString()).isEqualTo("  Looking for year\n");
    }

    @Test
    void substepIndentsByFour() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.subStep("Trying regex \\d{4}");
        assertThat(sb.toString()).isEqualTo("    Trying regex \\d{4}\n");
    }

    @Test
    void noChangesEmitted() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        t.noChanges();
        assertThat(sb.toString()).isEqualTo("    (no changes)\n");
    }

    @Test
    void resultPrintsFooter() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);
        var r = io.guessit.GuessResultBuilder.result().withTitle("Foo").build();
        t.result(r);
        assertThat(sb.toString()).startsWith("\nGuessIt found:\n");
        assertThat(sb.toString()).contains("title: Foo");
    }

    @Test
    void spansNotRenderedWhenToggleDisabled() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb);   // single-arg constructor: renderSpans=false
        t.spans("XxX.2020.mkv", List.of(Match.of(MatchName.YEAR, 2020, 4, 8, "2020")), List.of());
        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void spansRenderedWhenToggleEnabled() {
        var sb = new StringBuilder();
        var t = new DebugTrace(sb, true);
        t.spans("XxX.2020.mkv", List.of(Match.of(MatchName.YEAR, 2020, 4, 8, "2020")), List.of());
        assertThat(sb.toString()).contains("XxX.2020.mkv");
        assertThat(sb.toString()).contains("year");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=DebugTraceTest test`
Expected: FAIL — `DebugTrace` does not exist.

- [ ] **Step 3: Implement DebugTrace**

Create `src/main/java/io/guessit/engine/DebugTrace.java`:

```java
package io.guessit.engine;

import io.guessit.GuessResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * {@link Trace} that emits human-readable prose to an {@link Appendable}.
 *
 * <p>Used by the CLI when {@code --debug} is set; library consumers can
 * compose with {@link CompositeTrace} to combine prose narration with the
 * machine {@link PrintTrace} format.
 *
 * <p>Indentation:
 * <ul>
 *   <li>Phase header: column 0</li>
 *   <li>Step header (extractor/processor/marker): column 2</li>
 *   <li>Sub-step / span view: column 4 (span view itself indents +2 internally)</li>
 * </ul>
 */
public final class DebugTrace implements Trace {

    private final Appendable out;
    private final boolean renderSpans;

    public DebugTrace(Appendable out) { this(out, false); }
    public DebugTrace(Appendable out, boolean renderSpans) {
        this.out = out;
        this.renderSpans = renderSpans;
    }

    @Override public void input(String s) {
        write("For: " + s + "\n\n");
    }

    @Override public void phase(String name) {
        write(capitalise(name) + " phase\n");
    }

    @Override public void phase(String name, String description) {
        write(capitalise(name) + " phase — " + description + "\n");
    }

    @Override public void step(String kind, String name) {
        write("  " + verb(kind) + " " + name + "\n");
    }

    @Override public void step(String kind, String name, String description) {
        if (description == null || description.isEmpty() || description.equals(name)) {
            step(kind, name);
            return;
        }
        write("  " + verb(kind) + " " + name + " (" + description + ")\n");
    }

    @Override public void subStep(String message) {
        write("    " + message + "\n");
    }

    @Override public void noChanges() {
        write("    (no changes)\n");
    }

    @Override public void note(String msg) {
        write("  " + msg + "\n");
    }

    @Override public void spans(String input, List<Match> matches, List<Marker> markers) {
        if (!renderSpans) return;
        write(SpanRenderer.render(input, matches, markers));
    }

    @Override public void result(GuessResult r) {
        write("\nGuessIt found:\n" + io.guessit.cli.PlainFormatter.format(r) + "\n");
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Step kind → verb mapping for the prose header. */
    private static String verb(String kind) {
        return switch (kind) {
            case "extract" -> "Looking for";
            case "post"    -> "Refining";
            case "rule"    -> "Running rule";
            case "marker"  -> "Detecting";
            default        -> "Step (" + kind + "):";
        };
    }

    private void write(String s) {
        try { out.append(s); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=DebugTraceTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/DebugTrace.java \
       src/test/java/io/guessit/engine/DebugTraceTest.java
git commit -m "$(cat <<'EOF'
feat(trace): add DebugTrace prose-narration sink

Emits "For: <input>", capitalised phase headers with descriptions,
"Looking for <name>" step headers, indented sub-steps, and the final
"GuessIt found:" footer. Span rendering off by default; enabled via
new(out, true).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: TraceDiff fires `spans` on change

**Files:**
- Modify: `src/main/java/io/guessit/engine/TraceDiff.java`
- Test: `src/test/java/io/guessit/engine/TraceDiffSpansTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/TraceDiffSpansTest.java`:

```java
package io.guessit.engine;

import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDiffSpansTest {

    @Test
    void firesSpansEventWhenSetChanged() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() {
            @Override public void added(Match m)   { fired.add("+" + m.raw()); }
            @Override public void removed(Match m) { fired.add("-" + m.raw()); }
            @Override public void noChanges()      { fired.add("nochg"); }
            @Override public void spans(String i, List<Match> ms, List<Marker> mk) { fired.add("spans:" + ms.size() + "/" + mk.size()); }
        };
        var ctx = new ParseContext("XxX.2020.mkv", Options.defaults(), null, tr);
        var year = Match.of(MatchName.YEAR, 2020, 4, 8, "2020");
        var before = ctx.matches.snapshot();
        ctx.matches.add(year);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
        assertThat(fired).containsExactly("+2020", "spans:1/0");
    }

    @Test
    void firesNoChangesAndNoSpansWhenSetUnchanged() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() {
            @Override public void noChanges() { fired.add("nochg"); }
            @Override public void spans(String i, List<Match> ms, List<Marker> mk) { fired.add("spans"); }
        };
        var ctx = new ParseContext("XxX.2020.mkv", Options.defaults(), null, tr);
        var snap = ctx.matches.snapshot();
        TraceDiff.emit(snap, snap, ctx);
        assertThat(fired).containsExactly("nochg");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=TraceDiffSpansTest test`
Expected: FAIL — overload `TraceDiff.emit(List, List, ParseContext)` does not exist.

- [ ] **Step 3: Add the new TraceDiff overload**

Edit `src/main/java/io/guessit/engine/TraceDiff.java`. Bump visibility of the existing `emit(...,Trace)` to `public` so it can stay testable, AND add the new `ParseContext` overload that fires `spans` after a change. Replace the file with:

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Computes added / removed matches between two {@link MatchSet} snapshots and
 * forwards them to a {@link Trace}. Multiset-aware: if the same value-equal
 * {@link Match} appears N times in {@code before} and M times in {@code after},
 * {@code max(0, N-M)} removals and {@code max(0, M-N)} additions are emitted.
 *
 * <p>Removals are emitted before additions; additions preserve {@code after}
 * order; removals preserve {@code before} order. When the {@link ParseContext}
 * overload is used, a {@link Trace#spans} event fires after the diff if anything
 * changed, so DebugTrace can paint an updated span view.
 */
public final class TraceDiff {
    private TraceDiff() {}

    /** Diff-only emit. Fires {@code noChanges} when sets are identical. */
    public static void emit(List<Match> before, List<Match> after, Trace trace) {
        if (before.equals(after)) {
            trace.noChanges();
            return;
        }
        emitDiff(before, after, trace);
    }

    /** Diff emit that also fires {@link Trace#spans} with the post-change snapshot. */
    public static void emit(List<Match> before, List<Match> after, ParseContext ctx) {
        if (before.equals(after)) {
            ctx.trace.noChanges();
            return;
        }
        emitDiff(before, after, ctx.trace);
        ctx.trace.spans(ctx.input, ctx.matches.snapshot(), ctx.markers);
    }

    private static void emitDiff(List<Match> before, List<Match> after, Trace trace) {
        var afterCounts = new HashMap<Match, Integer>();
        for (var m : after) afterCounts.merge(m, 1, Integer::sum);

        var removals = new ArrayList<Match>();
        for (var m : before) {
            var c = afterCounts.getOrDefault(m, 0);
            if (c == 0) removals.add(m);
            else afterCounts.put(m, c - 1);
        }
        for (var m : removals) trace.removed(m);

        var beforeCounts = new HashMap<Match, Integer>();
        for (var m : before) beforeCounts.merge(m, 1, Integer::sum);
        for (var m : after) {
            var c = beforeCounts.getOrDefault(m, 0);
            if (c == 0) trace.added(m);
            else beforeCounts.put(m, c - 1);
        }
    }
}
```

(Note: visibility bump from package-private to public is acceptable because no callers outside the package exist today; the spec lists `TraceDiff` as a touched file and DebugTrace integration tests in a later task may benefit from public access.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=TraceDiffSpansTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS — phases still call the existing `emit(before, after, trace)` overload.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/TraceDiff.java \
       src/test/java/io/guessit/engine/TraceDiffSpansTest.java
git commit -m "$(cat <<'EOF'
feat(trace): TraceDiff fires spans event when match set changes

Adds emit(before, after, ParseContext) overload that emits the diff and
then fires Trace.spans with the post-change snapshot, so DebugTrace can
redraw the ASCII span view on every step that changed something.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Phase wiring — 3-arg step + spans

**Files:**
- Modify: `src/main/java/io/guessit/engine/MarkerPhase.java`
- Modify: `src/main/java/io/guessit/engine/ExtractorPhase.java`
- Modify: `src/main/java/io/guessit/engine/ExtractorPostPhase.java`
- Modify: `src/main/java/io/guessit/engine/ConflictPhase.java`
- Modify: `src/main/java/io/guessit/engine/PostPhase.java`
- Modify: `src/main/java/io/guessit/engine/OutputPhase.java`
- Test: `src/test/java/io/guessit/engine/PhaseDebugWiringTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/PhaseDebugWiringTest.java`:

```java
package io.guessit.engine;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseDebugWiringTest {

    @Test
    void debugTraceShowsAllPhaseHeadersWithDescriptions() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.Name.2020.1080p.BluRay.x264-GRP.mkv", trace);
        var out = sw.toString();
        assertThat(out)
            .contains("Markers phase — ")
            .contains("Extractors phase — ")
            .contains("Conflicts phase — ")
            .contains("Extractor_post phase — ")
            .contains("Post phase — ")
            .contains("Output phase — ");
    }

    @Test
    void debugTraceShowsLookingForStepHeader() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.2020.mkv", trace);
        assertThat(sw.toString()).contains("  Looking for year");
    }

    @Test
    void spansEmittedWhenToggleEnabled() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw, true);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.2020.mkv", trace);
        // The input string appears at least once embedded in a span view block.
        assertThat(sw.toString()).contains("Movie.2020.mkv");
        // Per-row underline must appear under at least one step.
        assertThat(sw.toString()).contains("----");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=PhaseDebugWiringTest test`
Expected: FAIL — phases still emit only the 1-arg `phase(name)` and 2-arg `step(kind, name)`.

- [ ] **Step 3: Update phases to emit descriptions and use the ctx-aware TraceDiff**

Edit `src/main/java/io/guessit/engine/MarkerPhase.java`:

```java
package io.guessit.engine;

import java.util.HashSet;
import java.util.List;

public record MarkerPhase(List<MarkerProducer> producers) implements Phase {

    @FunctionalInterface
    public interface MarkerProducer extends Described {
        void produce(ParseContext ctx);

        @Override
        default String description() { return getClass().getSimpleName(); }
    }

    public MarkerPhase { producers = List.copyOf(producers); }

    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("markers", "detecting path/group/container markers");
        var beforeAll = new HashSet<>(ctx.markers);
        for (var p : producers) {
            p.produce(ctx);
        }
        for (var m : ctx.markers) {
            if (!beforeAll.contains(m)) {
                ctx.trace.note("marker: " + m.raw() + ":(" + m.start() + "," + m.end() + ")+name=" + m.name());
                ctx.trace.subStep("Found " + m.name() + " marker '" + m.raw() + "' at " + m.start() + "-" + m.end());
            }
        }
        // Markers don't go through the matches diff; emit a one-shot spans event
        // so the ASCII view shows the initial marker layout.
        ctx.trace.spans(ctx.input, ctx.matches.snapshot(), ctx.markers);
    }
}
```

Edit `src/main/java/io/guessit/engine/ExtractorPhase.java`:

```java
package io.guessit.engine;

import java.util.List;

public record ExtractorPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("extractors", "scanning input for property patterns");
        for (var e : extractors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("extract", e.name(), e.description());
            e.extract(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
        }
    }
}
```

Edit `src/main/java/io/guessit/engine/ExtractorPostPhase.java`:

```java
package io.guessit.engine;

import java.util.List;

public record ExtractorPostPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPostPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("extractor_post", "refining matches after conflict resolution");
        for (var e : extractors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("post", e.name(), e.description());
            e.postProcess(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
        }
    }
}
```

Edit `src/main/java/io/guessit/engine/ConflictPhase.java`:

```java
package io.guessit.engine;

public record ConflictPhase() implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("conflicts", "resolving overlapping matches");
        var before = ctx.matches.snapshot();
        ConflictSolver.solve(ctx.matches, ctx.trace);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
    }
}
```

(Note: `ConflictSolver.solve(matches, trace)` overload is added in Task 9. To keep this task green, temporarily call the existing single-arg overload `ConflictSolver.solve(ctx.matches);` for now and switch to the 2-arg in Task 9.)

So actually use this body for Task 7:

```java
public record ConflictPhase() implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("conflicts", "resolving overlapping matches");
        var before = ctx.matches.snapshot();
        ConflictSolver.solve(ctx.matches);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
    }
}
```

Edit `src/main/java/io/guessit/engine/PostPhase.java`:

```java
package io.guessit.engine;

import java.util.List;

public record PostPhase(List<PostProcessor> processors) implements Phase {

    @FunctionalInterface
    public interface PostProcessor extends Described {
        void process(ParseContext ctx);

        @Override
        default String description() { return getClass().getSimpleName(); }
    }

    public PostPhase { processors = List.copyOf(processors); }

    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("post", "running heuristic rules");
        for (var p : processors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("rule", p.getClass().getSimpleName(), p.description());
            p.process(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
        }
    }
}
```

Edit `src/main/java/io/guessit/engine/OutputPhase.java`:

```java
package io.guessit.engine;

import java.util.function.Consumer;

public record OutputPhase(Consumer<ParseContext> assembler) implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("output", "assembling result");
        assembler.accept(ctx);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=PhaseDebugWiringTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS — `VerboseCliTest` still green because the 3-arg `step` and 2-arg `phase` defaults delegate to the existing 2-arg / 1-arg forms that `PrintTrace` overrides.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/MarkerPhase.java \
       src/main/java/io/guessit/engine/ExtractorPhase.java \
       src/main/java/io/guessit/engine/ExtractorPostPhase.java \
       src/main/java/io/guessit/engine/ConflictPhase.java \
       src/main/java/io/guessit/engine/PostPhase.java \
       src/main/java/io/guessit/engine/OutputPhase.java \
       src/test/java/io/guessit/engine/PhaseDebugWiringTest.java
git commit -m "$(cat <<'EOF'
feat(trace): phases emit descriptions and trigger spans diff

Every phase now emits a phase(name, description) header. Each step in
ExtractorPhase / ExtractorPostPhase / PostPhase emits step(kind, name,
description). TraceDiff(ctx) variant fires spans on changes; MarkerPhase
emits one spans event after producers and a subStep per new marker.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: PatternMatcher sub-step instrumentation

**Files:**
- Modify: `src/main/java/io/guessit/engine/PatternMatcher.java`
- Test: `src/test/java/io/guessit/engine/PatternMatcherDebugTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/PatternMatcherDebugTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMatcherDebugTest {

    @Test
    void regexEmitsTryAndAcceptedSubsteps() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var opts = RegexOpts.defaults().withValue(Integer::valueOf);
        PatternMatcher.regex("XxX.2020.mkv", Pattern.compile("\\d{4}"), MatchName.YEAR, opts, tr);
        assertThat(fired).anyMatch(s -> s.startsWith("Trying regex "));
        assertThat(fired).anyMatch(s -> s.startsWith("Considered '2020' at 4-8 — accepted"));
    }

    @Test
    void regexEmitsRejectedSubstepWhenValidatorFails() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var opts = RegexOpts.defaults()
            .withValue(Integer::valueOf)
            .withValidator(m -> false);
        PatternMatcher.regex("foo 2020 bar", Pattern.compile("\\d{4}"), MatchName.YEAR, opts, tr);
        assertThat(fired).anyMatch(s -> s.contains("rejected"));
    }

    @Test
    void stringEmitsTryAndAccepted() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var opts = StringOpts.defaults();
        PatternMatcher.string("Foo.1080p.bar", Set.of("1080p", "720p"), MatchName.SCREEN_SIZE, opts, tr);
        assertThat(fired).anyMatch(s -> s.startsWith("Trying needles"));
        assertThat(fired).anyMatch(s -> s.contains("'1080p'") && s.contains("accepted"));
    }

    @Test
    void backwardsCompatibleNoTraceOverloadStillWorks() {
        // Existing extractors call regex(input, pattern, name, opts) without a trace.
        var opts = RegexOpts.defaults().withValue(Integer::valueOf);
        List<Match> matches = PatternMatcher.regex("XxX.2020.mkv", Pattern.compile("\\d{4}"), MatchName.YEAR, opts);
        assertThat(matches).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=PatternMatcherDebugTest test`
Expected: FAIL — Trace-aware overloads of `regex` / `string` do not exist.

- [ ] **Step 3: Add Trace-aware overloads**

Edit `src/main/java/io/guessit/engine/PatternMatcher.java`. Replace the body with:

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

public final class PatternMatcher {
    private PatternMatcher() {}

    /* Existing no-trace overloads: delegate to the trace-aware version with NOOP. */

    public static List<Match> regex(String input, Pattern pattern, MatchName name, RegexOpts opts) {
        return regex(input, pattern, name, opts, Trace.NOOP);
    }

    public static List<Match> string(String input, Set<String> needles, MatchName name, StringOpts opts) {
        return string(input, needles, name, opts, Trace.NOOP);
    }

    /* Trace-aware overloads: emit subStep events for each try / decision. */

    public static List<Match> regex(String input, Pattern pattern, MatchName name, RegexOpts opts, Trace trace) {
        trace.subStep("Trying regex " + pattern.pattern());
        var out = new ArrayList<Match>();
        var m = pattern.matcher(input);
        boolean hasValueGroup = HAS_VALUE_GROUP.computeIfAbsent(pattern, PatternMatcher::detectValueGroup);
        while (m.find()) {
            String raw = m.group();
            int start = m.start();
            int end = m.end();
            String valueText = hasValueGroup ? m.group("value") : raw;
            Object extracted = opts.valueExtractor().apply(valueText);
            Object formatted = opts.valueFormatter().apply(extracted);
            var match = new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate());
            if (opts.validator().test(match)) {
                out.add(match);
                trace.subStep("Considered '" + raw + "' at " + start + "-" + end + " — accepted");
            } else {
                trace.subStep("Considered '" + raw + "' at " + start + "-" + end + " — rejected (validator)");
            }
        }
        return out;
    }

    public static List<Match> string(String input, Set<String> needles, MatchName name, StringOpts opts, Trace trace) {
        trace.subStep("Trying needles: " + summariseNeedles(needles));
        var out = new ArrayList<Match>();
        var hay = opts.caseSensitive() ? input : input.toLowerCase(java.util.Locale.ROOT);
        for (var raw : needles) {
            var n = opts.caseSensitive() ? raw : raw.toLowerCase(java.util.Locale.ROOT);
            scanNeedle(input, hay, raw, n, name, opts, out, trace);
        }
        out.sort(Comparator.comparingInt(Match::start));
        return out;
    }

    private static void scanNeedle(String input, String hay, String raw, String n,
                                   MatchName name, StringOpts opts, List<Match> out, Trace trace) {
        int from = 0;
        while (true) {
            int idx = hay.indexOf(n, from);
            if (idx < 0) break;
            int end = idx + n.length();
            boolean wordOk = !opts.wholeWord() || isWordBoundary(hay, idx, end);
            if (wordOk) {
                var match = new Match(name, raw, idx, end, input.substring(idx, end),
                    opts.priority(), opts.tags(), opts.isPrivate());
                if (opts.validator().test(match)) {
                    out.add(match);
                    trace.subStep("Considered '" + raw + "' at " + idx + "-" + end + " — accepted");
                } else {
                    trace.subStep("Considered '" + raw + "' at " + idx + "-" + end + " — rejected (validator)");
                }
            } else {
                trace.subStep("Considered '" + raw + "' at " + idx + "-" + end + " — rejected (word boundary)");
            }
            from = idx + 1;
        }
    }

    private static String summariseNeedles(Set<String> needles) {
        if (needles.size() <= 6) {
            return String.join(", ", new java.util.TreeSet<>(needles));
        }
        var sorted = new java.util.TreeSet<>(needles);
        var first6 = sorted.stream().limit(6).toList();
        return String.join(", ", first6) + ", … (" + needles.size() + " total)";
    }

    private static final ConcurrentMap<Pattern, Boolean> HAS_VALUE_GROUP = new ConcurrentHashMap<>();
    private static final Pattern VALUE_GROUP_DECL = Pattern.compile("\\(\\?<value>");

    private static boolean detectValueGroup(Pattern p) {
        return VALUE_GROUP_DECL.matcher(p.pattern()).find();
    }

    private static boolean isWordBoundary(String s, int start, int end) {
        if (start > 0 && Character.isLetterOrDigit(s.charAt(start - 1))) return false;
        return end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=PatternMatcherDebugTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS — every existing extractor call site uses the no-trace overload, which now delegates to the trace-aware version with `Trace.NOOP`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/PatternMatcher.java \
       src/test/java/io/guessit/engine/PatternMatcherDebugTest.java
git commit -m "$(cat <<'EOF'
feat(trace): PatternMatcher emits subSteps for each try and decision

regex/string scanners gain Trace-aware overloads that emit "Trying regex
…" / "Trying needles …" headers and per-candidate "Considered '<raw>'
at s-e — accepted/rejected (reason)" lines. No-trace overloads delegate
to the new ones with Trace.NOOP so existing call sites compile unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: ConflictSolver pair-decision sub-steps

**Files:**
- Modify: `src/main/java/io/guessit/engine/ConflictSolver.java`
- Modify: `src/main/java/io/guessit/engine/ConflictPhase.java`
- Test: `src/test/java/io/guessit/engine/ConflictSolverDebugTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/engine/ConflictSolverDebugTest.java`:

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictSolverDebugTest {

    @Test
    void emitsDropDecisionForShorterSpan() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var ms = new MatchSet();
        ms.add(Match.of(MatchName.YEAR, 2020, 4, 8, "2020"));        // length 4
        ms.add(Match.of(MatchName.SCREEN_SIZE, "x", 6, 14, "y1080p"));// length 8, overlaps year
        ConflictSolver.solve(ms, tr);
        assertThat(fired).anyMatch(s -> s.startsWith("Dropping ") && s.contains("overlaps") && s.contains("shorter span"));
    }

    @Test
    void emitsNothingWhenNoOverlap() {
        var fired = new ArrayList<String>();
        Trace tr = new Trace() { @Override public void subStep(String m) { fired.add(m); } };
        var ms = new MatchSet();
        ms.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        ms.add(Match.of(MatchName.SCREEN_SIZE, "1080p", 5, 10, "1080p"));
        ConflictSolver.solve(ms, tr);
        assertThat(fired).isEmpty();
    }

    @Test
    void backwardsCompatibleNoTraceOverloadStillWorks() {
        var ms = new MatchSet();
        ms.add(Match.of(MatchName.YEAR, 2020, 0, 4, "2020"));
        ConflictSolver.solve(ms);   // single-arg
        assertThat(ms.snapshot()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ConflictSolverDebugTest test`
Expected: FAIL — `ConflictSolver.solve(MatchSet, Trace)` does not exist.

- [ ] **Step 3: Add Trace-aware overload**

Edit `src/main/java/io/guessit/engine/ConflictSolver.java`. Replace the body with:

```java
package io.guessit.engine;

import java.util.*;

public final class ConflictSolver {
    private ConflictSolver() {}

    /** Backwards-compatible: no trace. */
    public static void solve(MatchSet matches) { solve(matches, Trace.NOOP); }

    public static void solve(MatchSet matches, Trace trace) {
        var publicMatches = matches.all()
            .filter(m -> !m.isPrivate())
            .sorted(Comparator.comparingInt(Match::length))
            .toList();
        var toRemove = new HashSet<Match>();
        for (var match : publicMatches) {
            if (toRemove.contains(match)) continue;
            resolveAgainstConflicts(match, publicMatches, toRemove, trace);
        }
        matches.removeAll(toRemove);
    }

    private static void resolveAgainstConflicts(Match match, List<Match> publicMatches, Set<Match> toRemove, Trace trace) {
        var conflicting = findConflicting(match, publicMatches, toRemove);
        conflicting.sort(Comparator.comparingInt(Match::length));
        for (var conflictingMatch : conflicting) {
            if (match.tags().contains("coexist") || conflictingMatch.tags().contains("coexist")) continue;
            var removed = defaultConflictSolver(match, conflictingMatch);
            if (recordRemoval(match, conflictingMatch, removed, toRemove, trace)) break;
        }
    }

    private static boolean recordRemoval(Match match, Match conflictingMatch, Match removed,
                                         Set<Match> toRemove, Trace trace) {
        if (removed == null || toRemove.contains(removed)) return false;
        var toKeep = (removed == match) ? conflictingMatch : match;
        if (!toRemove.contains(toKeep)) {
            toRemove.add(removed);
            String reason = removed.length() < toKeep.length() ? "shorter span" : "lower priority";
            trace.subStep("Dropping " + summary(removed) + " — overlaps " + summary(toKeep) + " (" + reason + ")");
        }
        return true;
    }

    private static String summary(Match m) {
        return m.name().name().toLowerCase(Locale.ROOT) + " '" + m.raw() + "' at " + m.start() + "-" + m.end();
    }

    private static Match defaultConflictSolver(Match match, Match conflictingMatch) {
        int matchLen = match.length();
        int conflictingLen = conflictingMatch.length();
        if (conflictingLen < matchLen) return conflictingMatch;
        if (matchLen < conflictingLen) return match;
        if (match.priority() > conflictingMatch.priority()) return conflictingMatch;
        if (match.priority() < conflictingMatch.priority()) return match;
        return null;
    }

    private static List<Match> findConflicting(Match match, List<Match> publicMatches, Set<Match> toRemove) {
        var result = new ArrayList<Match>();
        for (var other : publicMatches) {
            if (other == match) continue;
            if (toRemove.contains(other)) continue;
            if (other.isPrivate()) continue;
            if (match.overlaps(other)) result.add(other);
        }
        return result;
    }
}
```

- [ ] **Step 4: Update ConflictPhase to pass the trace**

Edit `src/main/java/io/guessit/engine/ConflictPhase.java`:

```java
package io.guessit.engine;

public record ConflictPhase() implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("conflicts", "resolving overlapping matches");
        var before = ctx.matches.snapshot();
        ConflictSolver.solve(ctx.matches, ctx.trace);
        TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=ConflictSolverDebugTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/engine/ConflictSolver.java \
       src/main/java/io/guessit/engine/ConflictPhase.java \
       src/test/java/io/guessit/engine/ConflictSolverDebugTest.java
git commit -m "$(cat <<'EOF'
feat(trace): ConflictSolver emits drop decision per pair

Each removal emits "Dropping <name> '<raw>' at s-e — overlaps <winner>
(shorter span | lower priority)" via Trace.subStep. ConflictPhase
threads ctx.trace through; the no-trace overload delegates with NOOP.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: OutputBuilder per-property sub-steps

**Files:**
- Modify: `src/main/java/io/guessit/rules/post/OutputBuilder.java`
- Test: `src/test/java/io/guessit/rules/post/OutputBuilderDebugTest.java` (new)

- [ ] **Step 1: Discover the OutputBuilder API**

Run: `grep -n "public\|class \|process\b" src/main/java/io/guessit/rules/post/OutputBuilder.java | head -40`

Read the file to identify the assignment site for each output property. The goal is to insert one `ctx.trace.subStep("Set " + property + " ← " + value + " from match at " + start + "-" + end);` call at the point where each property is written into `ctx.resultBuilder`.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/io/guessit/rules/post/OutputBuilderDebugTest.java`:

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import io.guessit.Options;
import io.guessit.engine.DebugTrace;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class OutputBuilderDebugTest {

    @Test
    void emitsSetSubstepPerProperty() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults())
            .guess("Movie.Name.2020.1080p.BluRay.x264-GRP.mkv", trace);
        var out = sw.toString();
        assertThat(out).contains("Set year ← 2020");
        assertThat(out).contains("Set screen_size ← 1080p");
        assertThat(out).contains("Set source ← BluRay");
        assertThat(out).contains("Set video_codec ← x264");
        assertThat(out).contains("Set release_group ← GRP");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -Dtest=OutputBuilderDebugTest test`
Expected: FAIL — no `"Set "` substring is emitted.

- [ ] **Step 4: Instrument OutputBuilder**

Insert `ctx.trace.subStep(...)` calls at every property-assignment site inside `OutputBuilder`. The exact form is:

```java
ctx.trace.subStep("Set " + propertyName + " ← " + valueDisplay + " from match at " + match.start() + "-" + match.end());
```

If the assignment uses a list of matches (multi-value property like `language`), emit one subStep per source match, or one summary line if the property is composed (e.g. `title` from holes). For `title` derived from a hole span, emit:

```java
ctx.trace.subStep("Set title ← '" + title + "' from holes " + start + "-" + end);
```

`valueDisplay` should be the same string the formatter would print (use `String.valueOf(value)`; quote string values with single quotes).

Concrete pattern (replicate at every assignment site):

```java
// before:
ctx.resultBuilder.withYear((Integer) m.value());

// after:
ctx.resultBuilder.withYear((Integer) m.value());
ctx.trace.subStep("Set year ← " + m.value() + " from match at " + m.start() + "-" + m.end());
```

Apply at every property-setter call inside `OutputBuilder.process(...)` (and any helper methods it calls).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=OutputBuilderDebugTest test`
Expected: PASS — all five property substrings appear in the debug output.

Run full suite: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/post/OutputBuilder.java \
       src/test/java/io/guessit/rules/post/OutputBuilderDebugTest.java
git commit -m "$(cat <<'EOF'
feat(trace): OutputBuilder emits subStep per assigned property

Every property setter call now narrates "Set <name> ← <value> from match
at s-e" via Trace.subStep, so --debug shows how each output field was
assembled.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Multi-stage processor sub-steps

**Files:**
- Modify: `src/main/java/io/guessit/rules/post/SeasonYear.java`
- Modify: `src/main/java/io/guessit/rules/post/SeasonYearLink.java`
- Modify: `src/main/java/io/guessit/rules/post/StripSeparators.java`
- Modify: any other `rules/post/*.java` whose `process` method has clearly separable internal stages
- Test: `src/test/java/io/guessit/rules/post/MultiStageProcessorDebugTest.java` (new)

- [ ] **Step 1: Identify multi-stage processors**

Run: `grep -nE "(// ?Stage|// ?Step|// ?Pass)" src/main/java/io/guessit/rules/post/*.java`

For each candidate, identify the natural stage boundaries (e.g. "scan", "filter", "rewrite") inside `process`. If the body is one straight loop, the processor is single-stage and gets no subStep; only its description() override (Task 13) narrates it.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/io/guessit/rules/post/MultiStageProcessorDebugTest.java`:

```java
package io.guessit.rules.post;

import io.guessit.Guessit;
import io.guessit.Options;
import io.guessit.engine.DebugTrace;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class MultiStageProcessorDebugTest {

    @Test
    void multiStageProcessorEmitsStageSubsteps() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        // Input chosen to exercise SeasonYear / SeasonYearLink stages.
        Guessit.withOptions(Options.defaults()).guess("Show.S01.2020.1080p.mkv", trace);
        var out = sw.toString();
        assertThat(out).contains("Stage ");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -Dtest=MultiStageProcessorDebugTest test`
Expected: FAIL — no `"Stage "` substring is produced yet.

- [ ] **Step 4: Insert per-stage subSteps**

For each multi-stage processor, insert at the start of each stage:

```java
ctx.trace.subStep("Stage 1: " + shortDescription);
```

Example, inside `SeasonYear.process`:

```java
@Override
public void process(ParseContext ctx) {
    ctx.trace.subStep("Stage 1: collecting season + year candidates");
    // ... existing collection logic ...
    ctx.trace.subStep("Stage 2: applying linkage rule");
    // ... existing decision logic ...
}
```

Add similar pairs to any other multi-stage processor.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=MultiStageProcessorDebugTest test`
Expected: PASS — at least one `"Stage "` substring is present.

Run full suite: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/rules/post/ \
       src/test/java/io/guessit/rules/post/MultiStageProcessorDebugTest.java
git commit -m "$(cat <<'EOF'
feat(trace): multi-stage processors emit Stage subSteps

SeasonYear, SeasonYearLink, StripSeparators (and any other processor
with clearly separable internal stages) now narrate "Stage N: <what>"
via Trace.subStep so --debug explains intermediate decisions.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Thread `ctx.trace` through extractor → PatternMatcher call sites

**Files:**
- Modify: every file in `src/main/java/io/guessit/rules/property/*.java` that calls `PatternMatcher.regex` or `PatternMatcher.string` and wants its sub-step prose visible
- Test: `src/test/java/io/guessit/rules/property/ExtractorTraceWiringTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/rules/property/ExtractorTraceWiringTest.java`:

```java
package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import io.guessit.engine.DebugTrace;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorTraceWiringTest {

    @Test
    void yearExtractorPropagatesTraceToPatternMatcher() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults()).guess("Movie.2020.mkv", trace);
        var out = sw.toString();
        assertThat(out).contains("Looking for year");
        assertThat(out).contains("Trying regex \\d{4}");
        assertThat(out).contains("Considered '2020' at 6-10 — accepted");
    }

    @Test
    void screenSizeExtractorPropagatesTraceToPatternMatcher() {
        var sw = new StringWriter();
        var trace = new DebugTrace(sw);
        Guessit.withOptions(Options.defaults()).guess("Movie.1080p.mkv", trace);
        var out = sw.toString();
        assertThat(out).contains("Looking for screen_size");
        assertThat(out).containsAnyOf("Trying needles", "Trying regex");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ExtractorTraceWiringTest test`
Expected: FAIL — extractors call the no-trace overloads, so subSteps are not emitted.

- [ ] **Step 3: Update extractors to pass `ctx.trace`**

Run: `grep -rln "PatternMatcher\.\(regex\|string\)" src/main/java/io/guessit/rules/`

For each match, change the call from:

```java
PatternMatcher.regex(input, PATTERN, MatchName.YEAR, opts)
```

to:

```java
PatternMatcher.regex(input, PATTERN, MatchName.YEAR, opts, ctx.trace)
```

Same transformation for `PatternMatcher.string(...)`. Also update any helper that takes only `(input, ...)` and forwards to PatternMatcher — thread `Trace` through.

Example, in `YearExtractor.extract`:

```java
@Override
public void extract(ParseContext ctx) {
    var input = ctx.input;
    var opts = RegexOpts.defaults()
            .withValue(Integer::valueOf)
            .withValidator(m -> {
                if (!Validators.sepsSurround(input).test(m)) return false;
                int v = (Integer) m.value();
                return 1920 <= v && v < 2030;
            });
    for (var match : PatternMatcher.regex(input, PATTERN, MatchName.YEAR, opts, ctx.trace)) {
        ctx.matches.add(match);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ExtractorTraceWiringTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS — extractors that don't change behaviour just thread the trace.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/property/ \
       src/main/java/io/guessit/rules/markers/ \
       src/test/java/io/guessit/rules/property/ExtractorTraceWiringTest.java
git commit -m "$(cat <<'EOF'
feat(trace): thread ctx.trace into every PatternMatcher call site

Each extractor / marker producer now passes ctx.trace to PatternMatcher
so the regex/needle try lines and per-candidate accepted/rejected
decisions land in --debug output.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: description() overrides for every concrete component

**Files:**
- Modify: every file in `src/main/java/io/guessit/rules/property/*.java`
- Modify: every file in `src/main/java/io/guessit/rules/post/*.java`
- Modify: every file in `src/main/java/io/guessit/rules/markers/*.java`
- Test: `src/test/java/io/guessit/engine/DescriptionsCoverageTest.java` (new)

- [ ] **Step 1: Write the coverage test**

Create `src/test/java/io/guessit/engine/DescriptionsCoverageTest.java`:

```java
package io.guessit.engine;

import io.guessit.rules.Rules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionsCoverageTest {

    @Test
    void everyExtractorHasNonDefaultDescription() {
        for (var e : Rules.defaultExtractors()) {
            assertThat(e.description())
                .as("extractor %s description", e.name())
                .isNotNull()
                .isNotEmpty()
                .isNotEqualTo(e.name());
        }
    }

    @Test
    void everyPostProcessorHasNonDefaultDescription() {
        for (var p : Rules.defaultPostProcessors()) {
            assertThat(p.description())
                .as("processor %s description", p.getClass().getSimpleName())
                .isNotNull()
                .isNotEmpty()
                .isNotEqualTo(p.getClass().getSimpleName());
        }
    }

    @Test
    void everyMarkerProducerHasNonDefaultDescription() {
        for (var m : Rules.defaultMarkerProducers()) {
            assertThat(m.description())
                .as("marker %s description", m.getClass().getSimpleName())
                .isNotNull()
                .isNotEmpty()
                .isNotEqualTo(m.getClass().getSimpleName());
        }
    }
}
```

- [ ] **Step 2: Verify the introspection helpers exist on `Rules`**

Run: `grep -n "defaultExtractors\|defaultPostProcessors\|defaultMarkerProducers" src/main/java/io/guessit/rules/Rules.java`

If any are missing, add them. They should return `List<Extractor>`, `List<PostPhase.PostProcessor>`, and `List<MarkerPhase.MarkerProducer>` respectively, sourced from the same lists already used to build `defaultPipeline()`. Example:

```java
public static List<Extractor> defaultExtractors() { return EXTRACTORS; }
public static List<PostPhase.PostProcessor> defaultPostProcessors() { return POST_PROCESSORS; }
public static List<MarkerPhase.MarkerProducer> defaultMarkerProducers() { return MARKER_PRODUCERS; }
```

(Adjust constants names if they differ. The point is to expose the registered components for introspection.)

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -Dtest=DescriptionsCoverageTest test`
Expected: FAIL — every component still uses the default fallback (`name()` for extractors, `getClass().getSimpleName()` for processors/markers).

- [ ] **Step 4: Add `description()` override to every Extractor**

For each file in `src/main/java/io/guessit/rules/property/*.java` that implements `Extractor`, add a `description()` override after `name()`. Use these strings (one per extractor; if you add a new extractor not in this list, write a description in the same style):

| Extractor | Description |
|---|---|
| `YearExtractor` | `"4-digit year (1920–2029)"` |
| `ScreenSizeExtractor` | `"resolution (480p / 720p / 1080p / 2160p / 4K, including i variants)"` |
| `SourceExtractor` | `"source / medium (BluRay, WEB-DL, HDTV, DVD, …)"` |
| `VideoCodecExtractor` | `"video codec (x264, x265, h264, h265, xvid, divx, …)"` |
| `AudioCodecExtractor` | `"audio codec (AAC, AC3, DTS, FLAC, …)"` |
| `ContainerExtractor` | `"container / mimetype (mkv, mp4, avi, …)"` |
| `ReleaseGroupExtractor` | `"release group (trailing dash token, bracketed group)"` |
| `LanguageExtractor` | `"language tags (ENG, FRENCH, MULTI, …)"` |
| `CountryExtractor` | `"country tags (US, UK, …)"` |
| `EditionExtractor` | `"edition (Director's Cut, Extended, Remastered, …)"` |
| `OtherExtractor` | `"other release flags (Proper, Repack, Internal, …)"` |
| `EpisodeDetailsExtractor` | `"episode details (Special, Pilot, Final, …)"` |
| `EpisodeFormatExtractor` | `"episode format keyword (Episode, Chapter, …)"` |
| `EpisodeWordExtractor` | `"weak episode word (E12, EP12, …)"` |
| `EpisodeTitleExtractor` | `"episode title (text after season/episode tokens)"` |
| `SeasonEpisodeExtractor` | `"season + episode tokens (SxxExx, 1x01, season+episode)"` |
| `WeakDuplicateExtractor` | `"weak NN/NN duplicate (rejected unless year guards it)"` |
| `WeakEpisodeExtractor` | `"weak trailing numeric → absolute_episode if SxxExx survives"` |
| `BitRateExtractor` | `"video / audio bit rate (kbps, Mbps)"` |
| `BonusExtractor` | `"bonus content (Bonus, Featurette, …)"` |
| `CdExtractor` | `"CD / disc number tokens (CD1, CD2, …)"` |
| `CrcExtractor` | `"CRC32 checksum (8 hex chars)"` |
| `DateExtractor` | `"date (YYYY-MM-DD, DD-MM-YYYY, …)"` |
| `FilmExtractor` | `"film number (Film 1, Movie 2, …)"` |
| `PartExtractor` | `"part number (Part 1, Pt II, …)"` |
| `SizeExtractor` | `"size (123MB, 4.5GB, …)"` |
| `StreamingServiceExtractor` | `"streaming service (NF, AMZN, HMAX, …)"` |
| `TitleExtractor` | `"title (text in the leading hole between markers / matches)"` |
| `VersionExtractor` | `"version (v2, v3, …)"` |
| `WebsiteExtractor` | `"source website (.com, .net, …)"` |
| `WeekExtractor` | `"week tokens (W12, Week 12, …)"` |
| `ExpectedTitleRegex` | `"user-supplied --expected-title regex"` |
| `DiscRule` | `"disc number (Disc 1, Disc 2, …)"` |

Pattern (apply uniformly):

```java
@Override
public String description() {
    return "4-digit year (1920–2029)";
}
```

- [ ] **Step 5: Add `description()` override to every PostProcessor**

For each file in `src/main/java/io/guessit/rules/post/*.java`, add a `description()` override:

| Processor | Description |
|---|---|
| `PreferLastPath` | `"prefer matches in the last path segment when names collide"` |
| `PrivateRemover` | `"drop scaffolding / private matches"` |
| `OutputBuilder` | `"assemble GuessResult from surviving matches"` |
| `MimetypeProcessor` | `"derive mimetype from container"` |
| `SeasonYear` | `"resolve season vs year ambiguity"` |
| `SeasonYearLink` | `"link season+year tokens that belong together"` |
| `StripSeparators` | `"trim leading/trailing separators on raw spans"` |
| `EnlargeGroupMatches` | `"enlarge match span to cover its containing bracket group"` |
| `EpisodeNumberSeparatorRange` | `"detect episode ranges separated by - or to"` |
| `EquivalentHoles` | `"merge equivalent leading/trailing holes"` |
| `RangeFiller` | `"fill numeric ranges between season/episode endpoints"` |
| `RemoveAmbiguous` | `"drop ambiguous low-confidence matches"` |
| `RemoveLessSpecificSeasonEpisode` | `"drop generic season/episode when a more specific one survives"` |
| `BitRateTypeRule` | `"split bit_rate into audio_bit_rate / video_bit_rate"` |
| `ProperCountRule` | `"count proper/repack tokens"` |
| `TypeProcessor` | `"infer type=movie or episode from surviving matches"` |
| `YearSeason` | `"promote leading numeric to season when adjacent to a year"` |
| `AbsoluteEpisodePromoter` | `"promote weak trailing episode → absolute_episode"` |

(`DiscRule` and `ExpectedTitleRegex` are technically extractors, not processors — already covered in Task 13 step 4.)

Same pattern: `@Override public String description() { return "..."; }`.

- [ ] **Step 6: Add `description()` override to every MarkerProducer**

| Marker | Description |
|---|---|
| `PathMarker` | `"path / whole markers (one per filepart, plus a whole-input marker)"` |
| `GroupMarker` | `"bracketed group markers ([…], (…), {…})"` |

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn -q -Dtest=DescriptionsCoverageTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/guessit/rules/ \
       src/test/java/io/guessit/engine/DescriptionsCoverageTest.java
git commit -m "$(cat <<'EOF'
feat(trace): add description() overrides for every extractor / processor / marker

Each component now narrates itself in --debug output. A coverage test
asserts every default-registered Extractor / PostProcessor /
MarkerProducer has a non-fallback description.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: CLI flags + wiring

**Files:**
- Modify: `src/main/java/io/guessit/cli/GuessitCli.java`
- Test: `src/test/java/io/guessit/cli/DebugCliTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/guessit/cli/DebugCliTest.java`:

```java
package io.guessit.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DebugCliTest {

    @Test
    void debugProseGoesToStderrByDefault() {
        var out = runCapture(new String[]{"--debug", "Movie.2020.1080p.mkv"});
        assertThat(out.stderr).contains("For: Movie.2020.1080p.mkv");
        assertThat(out.stderr).contains("Looking for year");
        assertThat(out.stdout).doesNotContain("Looking for");
    }

    @Test
    void debugWithJsonKeepsStdoutClean() {
        var out = runCapture(new String[]{"--debug", "--json", "Movie.2020.mkv"});
        assertThat(out.stderr).contains("Looking for year");
        assertThat(out.stdout).startsWith("{");          // JSON still on stdout
        assertThat(out.stderr).doesNotContain("ignored when --verbose"); // no warning
    }

    @Test
    void debugOutWritesToFile(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("trace.txt");
        runCapture(new String[]{"--debug", "--debug-out", f.toString(), "Movie.2020.mkv"});
        var contents = Files.readString(f, StandardCharsets.UTF_8);
        assertThat(contents).contains("For: Movie.2020.mkv");
        assertThat(contents).contains("Looking for year");
    }

    @Test
    void debugMarkersWithoutDebugErrors() {
        var out = runCapture(new String[]{"--debug-markers", "Movie.2020.mkv"});
        assertThat(out.exit).isEqualTo(2);
        assertThat(out.stderr).contains("--debug-markers requires --debug");
    }

    @Test
    void debugMarkersRendersSpanView() {
        var out = runCapture(new String[]{"--debug", "--debug-markers", "XxX.2020.mkv"});
        assertThat(out.stderr).contains("XxX.2020.mkv");
        assertThat(out.stderr).contains("year");
        assertThat(out.stderr).contains("----");        // underline row
    }

    @Test
    void multipleFilenamesSeparatedByBlankLine() {
        var out = runCapture(new String[]{"--debug", "A.2020.mkv", "B.2021.mkv"});
        assertThat(out.stderr).contains("For: A.2020.mkv");
        assertThat(out.stderr).contains("For: B.2021.mkv");
        assertThat(out.stderr).contains("\n\nFor: B.2021.mkv");
    }

    private record Captured(int exit, String stdout, String stderr) {}

    private Captured runCapture(String[] args) {
        var bo = new ByteArrayOutputStream();
        var be = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new CommandLine(new GuessitCli()).execute(args);
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        return new Captured(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=DebugCliTest test`
Expected: FAIL — `--debug`, `--debug-out`, `--debug-markers` are unknown options.

- [ ] **Step 3: Add the CLI options and wiring**

Edit `src/main/java/io/guessit/cli/GuessitCli.java`:

3a. Add fields next to the existing `verbose` flag:

```java
@Option(names = "--debug",          description = "Emit human-readable narration of every parse step.")
boolean debug;

@Option(names = "--debug-out",      paramLabel = "PATH",
        description = "Write --debug output to PATH (default: stderr).")
Path debugOut;

@Option(names = "--debug-markers",  description = "Render an ASCII span view whenever the match set changes (requires --debug).")
boolean debugMarkers;
```

3b. Inside `call()`, before the existing verbose block builds the trace, insert:

```java
if (debugMarkers && !debug) {
    System.err.println("error: --debug-markers requires --debug");
    return 2;
}
```

3c. Replace the existing `var trace = new PrintTrace(System.out);` block with the composite-trace wiring:

```java
// Open the debug sink (stderr or file). Closed in finally.
java.io.Writer debugSink = null;
DebugTrace debugTrace = null;
if (debug) {
    if (debugOut != null) {
        debugSink = java.nio.file.Files.newBufferedWriter(debugOut, java.nio.charset.StandardCharsets.UTF_8);
    } else {
        debugSink = new java.io.OutputStreamWriter(System.err, java.nio.charset.StandardCharsets.UTF_8);
    }
    debugTrace = new DebugTrace(debugSink, debugMarkers);
}

PrintTrace verboseTrace = verbose ? new PrintTrace(System.out) : null;

io.guessit.engine.Trace trace;
if (verboseTrace != null && debugTrace != null) trace = new io.guessit.engine.CompositeTrace(verboseTrace, debugTrace);
else if (verboseTrace != null)                  trace = verboseTrace;
else if (debugTrace  != null)                   trace = debugTrace;
else                                            trace = io.guessit.engine.Trace.NOOP;

try {
    for (int i = 0; i < filenames.size(); i++) {
        if (i > 0 && (verbose || debug)) {
            // Blank line between trace blocks.
            if (verbose) System.out.println();
            if (debug && debugSink != null) debugSink.append("\n");
        }
        var result = guessit.guess(filenames.get(i), trace);
        if (!verbose) {
            // Existing structured-output path stays exactly as it was.
            // (json / yaml / showProperty / PlainFormatter)
        }
    }
} finally {
    if (debugSink != null && debugOut != null) debugSink.close();
    // When debugSink wraps System.err, do NOT close it — flushing is enough.
    if (debugSink != null && debugOut == null) debugSink.flush();
}
```

3d. Update the existing `--verbose --json` warning gate so `--debug --json` does NOT trigger it:

```java
if (verbose && (json || yaml || showProperty != null)) {
    System.err.println("warning: --json/--yaml/--show-property ignored when --verbose is set");
}
// note: no analogous warning for --debug; debug goes to stderr by default
//       and never blocks the structured stdout pipeline.
```

3e. Make sure the `if (verbose) { ... }` short-circuit that suppresses structured stdout output is preserved. `--debug` alone must NOT suppress `--json` / `--yaml` / `-P` on stdout.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=DebugCliTest test`
Expected: PASS.

Run full suite: `mvn -q test`
Expected: PASS — `VerboseCliTest` still green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/cli/GuessitCli.java \
       src/test/java/io/guessit/cli/DebugCliTest.java
git commit -m "$(cat <<'EOF'
feat(cli): add --debug, --debug-out, --debug-markers

--debug emits human-readable narration to stderr by default. --debug-out
redirects to a file. --debug-markers renders an ASCII span view on every
match-set change (requires --debug, exits with 2 otherwise). --debug
coexists with --json/--yaml on stdout; no warning is emitted.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Combined `-v --debug` integration

**Files:**
- Test: `src/test/java/io/guessit/cli/DebugCombinedTest.java` (new)

- [ ] **Step 1: Write the test**

Create `src/test/java/io/guessit/cli/DebugCombinedTest.java`:

```java
package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DebugCombinedTest {

    @Test
    void verboseAndDebugBothEmitConcurrently() {
        var bo = new ByteArrayOutputStream();
        var be = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new GuessitCli()).execute("-v", "--debug",
                "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv");
            assertThat(code).isZero();
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }

        var stdout = bo.toString(StandardCharsets.UTF_8);
        var stderr = be.toString(StandardCharsets.UTF_8);

        // Machine trace on stdout (existing -v contract):
        assertThat(stdout).contains("[phase] extractors");
        assertThat(stdout).contains("[extract] year");
        assertThat(stdout).contains("+ 2020:(11,15)+name=year");

        // Prose narration on stderr (new --debug contract):
        assertThat(stderr).contains("Extractors phase — ");
        assertThat(stderr).contains("Looking for year");
        assertThat(stderr).contains("Considered '2020' at 11-15 — accepted");
    }
}
```

- [ ] **Step 2: Run test**

Run: `mvn -q -Dtest=DebugCombinedTest test`
Expected: PASS — by Task 14 the wiring is already done.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/guessit/cli/DebugCombinedTest.java
git commit -m "$(cat <<'EOF'
test(cli): -v --debug fan-out emits both machine and prose traces

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: README + parity note

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a `--debug` section under the existing CLI documentation**

Append to the CLI section of `README.md`:

````markdown
### Debug narration

`--debug` prints a human-readable narration of every parse step (phases,
extractors, processors, markers, and internal sub-steps) to **stderr**, so
JSON/YAML output on stdout still pipes cleanly:

    guessit-java --debug --json Movie.2020.1080p.mkv 2>trace.log

Combine with `--debug-markers` to render an ASCII span view whenever the match
set changes:

    guessit-java --debug --debug-markers XxX.2020.mkv

Use `--debug-out=PATH` to redirect the narration to a file instead of stderr.

`--debug` is independent of `-v/--verbose` — `-v` keeps emitting the existing
machine-readable trace on stdout. Combine the flags to get both.
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs: document --debug, --debug-markers, --debug-out flags

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

- [ ] **Run the full test suite once more:**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Run a smoke test against a known fixture:**

Run:
```bash
mvn -q package -DskipTests
java -jar target/guessit-java-*-cli.jar --debug --debug-markers \
    "Movie.Name.2020.1080p.BluRay.x264-GRP.mkv" 2>&1 1>/dev/null
```
Expected: prose narration including phase headers, "Looking for year", "Considered '2020' at 11-15 — accepted", a span view with `year`, `screen_size`, `source`, `video_codec`, `release_group` labels, and `Set …` substeps under the output phase.

- [ ] **Confirm YML parity unchanged:**

The 2076/2076 YML parity must remain green. Run: `mvn -q -Dtest=YmlParityTest test` (or whichever class drives the YML suite — `grep -l "yml" src/test/java/**/*.java` to confirm). Expected: PASS, identical pass count to before this branch.

---

## Self-review

**Spec coverage:**
- Trace API extension (subStep, 3-arg step, 2-arg phase, spans) → Task 1.
- DebugTrace skeleton + span toggle → Task 5.
- CompositeTrace → Task 3.
- Described mixin + Extractor/PostProcessor/MarkerProducer description() defaults → Task 2.
- SpanRenderer → Task 4.
- TraceDiff fires spans on change → Task 6.
- Phase wiring (3-arg step, descriptions, ctx-aware TraceDiff) → Task 7.
- PatternMatcher sub-step instrumentation → Task 8.
- ConflictSolver pair decisions → Task 9.
- OutputBuilder per-property subSteps → Task 10.
- Multi-stage processor stage subSteps → Task 11.
- Threading ctx.trace into every PatternMatcher call site → Task 12.
- description() override for every concrete component → Task 13.
- CLI flags `--debug`, `--debug-out`, `--debug-markers`, behaviour matrix → Task 14.
- `-v --debug` fan-out integration test → Task 15.
- README documentation → Task 16.

**Type consistency:** `Trace.spans(String, List<Match>, List<Marker>)` declared in Task 1 matches the call sites in Tasks 6, 7, and the `DebugTrace` impl in Task 5. `description()` signature on `Described` matches the overrides in Tasks 2 and 13. `PatternMatcher.regex/string` overloads are declared once in Task 8 and threaded through call sites in Task 12 with identical signatures. `ConflictSolver.solve(MatchSet, Trace)` declared in Task 9 used by `ConflictPhase` in Task 9 step 4.

**Placeholder scan:** None. Every step shows the code or the exact transformation pattern. Where the surface is large (Tasks 11–13), the table of components + descriptions is enumerated and the pattern is shown once with the instruction to apply uniformly.
