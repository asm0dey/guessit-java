# Debug Mode: Prose Trace for guessit-java CLI

**Status:** approved design, awaiting plan
**Date:** 2026-05-07
**Related:** `2026-05-05-verbose-mode-design.md` (the existing `-v` machine trace this design extends without disturbing)

## Goal

Add a new `--debug` CLI flag that emits a **human-readable narration** of every parsing step — phases, extractors, processors, markers, plus internal sub-steps inside `PatternMatcher`, `ConflictSolver`, multi-stage processors, and `OutputPhase`. Library consumers can attach the same narration sink via the existing `Guessit.guess(input, Trace)` API.

`-v` and `--debug` are independent. They can be combined; events fan out to both sinks.

## Non-goals

- Replacing or reformatting `-v` output. `-v` keeps its existing machine format. `VerboseCliTest` must remain green.
- Localisation. Prose strings are English only.
- Translating internal data structures verbatim. Descriptions are summaries, not exhaustive dumps.
- Programmatic capture in JSON / structured form.
- Logging frameworks (SLF4J etc.). Plain `Appendable` sink only.

## Architecture

### Trace API extension

Add to existing `io.guessit.engine.Trace`:

```java
public interface Trace {
    // ...existing methods unchanged...

    /** Three-arg overload for steps that carry a human-readable description.
     *  PrintTrace ignores the description; DebugTrace uses it. */
    default void step(String kind, String name, String description) { step(kind, name); }

    /** Generic indented sub-event emitted from inside a step
     *  (PatternMatcher tries, ConflictSolver pair decisions, processor sub-stages,
     *  per-property output assignments). */
    default void subStep(String message) {}

    /** Phase headers with a human-readable description for prose narration. */
    default void phase(String name, String description) { phase(name); }

    /** Snapshot of all live spans (non-private matches + markers) plus the input.
     *  Emitted by phases when the match set changed within a step.
     *  DebugTrace with span rendering enabled paints an ASCII view; everything else no-ops. */
    default void spans(String input, List<Match> matches, List<Marker> markers) {}
}
```

No separate `decision()` helper — accepted/rejected/dropped events are all emitted via `subStep` with the verdict and reason embedded in the message.

`Trace.NOOP` continues to no-op everything. `PrintTrace` overrides only the methods it understands; the new `subStep` / 3-arg `step` / 2-arg `phase` default to either no-op or delegation to the existing 2-arg form, so `-v` output is byte-identical to before.

### DebugTrace (new)

```java
package io.guessit.engine;

public final class DebugTrace implements Trace {
    public DebugTrace(Appendable out) { ... }

    @Override public void input(String s)                         { ... }
    @Override public void phase(String name)                      { ... }   // fallback when no description
    @Override public void phase(String name, String description)  { ... }   // prose header
    @Override public void step(String kind, String name)          { ... }   // fallback
    @Override public void step(String kind, String name, String description) { ... }
    @Override public void subStep(String message)                 { ... }
    @Override public void added(Match m)                          { ... }   // emits prose, not machine span
    @Override public void removed(Match m)                        { ... }   // emits prose, not machine span
    @Override public void noChanges()                             { ... }   // "(no changes)"
    @Override public void note(String msg)                        { ... }
    @Override public void result(GuessResult r)                   { ... }
}
```

Indent rules (two-space per level):

- Column 0: phase header
- Column 2: step header (extractor / processor / marker / output prop)
- Column 4: sub-step lines (`Trying regex …`, `Considered …`, conflict pair decision, output assignment reason)

`DebugTrace` does **not** print the machine span format. It prints prose only. When the caller wants both, they wire a `CompositeTrace` that holds both `PrintTrace` and `DebugTrace`.

### SpanRenderer (new)

Static helper in `io.guessit.engine` that turns input + spans into a multi-line ASCII view:

```java
package io.guessit.engine;

public final class SpanRenderer {
    private SpanRenderer() {}

    /** Renders input string with underline + label rows for every span. */
    public static String render(String input, List<Match> matches, List<Marker> markers);
}
```

Rendering rules:

1. Combine matches (non-private) and markers into one list of spans `(start, end, label)`. Marker label = `marker.name`. Match label = `match.name().toLowerCase`.
2. Sort by `start`, then by `end` ascending.
3. Line 1: the input string verbatim.
4. Line 2: underline row — one `-` per char inside any span, two spaces between adjacent spans.
5. Subsequent rows: assign each span to the lowest available label row (no horizontal overlap with already-placed labels in that row). Place a `|` connector at the span midpoint in every row above the label row, then write the label centered on the span midpoint.
6. Truncate labels longer than the span width with `…` only when no row below can fit the full label.
7. Indent every output line by 2 spaces (sub-step indent level).

Sample output:

```
  XxX.2024.mkv
  ---  ---- ---
   |    |    |
 title  |    container
       year
```

### DebugTrace span-rendering toggle

```java
public DebugTrace(Appendable out)                       // prose only
public DebugTrace(Appendable out, boolean renderSpans)  // prose + span view
```

`renderSpans` defaults to `false`. When `true`, `DebugTrace.spans(...)` calls `SpanRenderer.render(...)` and writes the result. When `false`, `spans(...)` is a no-op.

### When `spans` is emitted

`TraceDiff.emit(before, after, trace)` already runs at the end of every step in every phase. Extend it:

```java
public static void emit(List<Match> before, List<Match> after, ParseContext ctx) {
    boolean changed = false;
    // ... existing added/removed iteration sets `changed = true` if anything fires ...
    if (changed) ctx.trace.spans(ctx.input, ctx.matches.snapshot(), ctx.markers);
    else         ctx.trace.noChanges();
}
```

(Existing `TraceDiff.emit(before, after, trace)` overload kept for tests; phase callers switch to the `ParseContext` overload.)

`MarkerPhase` also emits `spans` once after producers run, since markers are not in the match diff path.

### CompositeTrace (new)

```java
package io.guessit.engine;

public final class CompositeTrace implements Trace {
    private final List<Trace> sinks;
    public CompositeTrace(Trace... sinks) { this.sinks = List.of(sinks); }

    // Each method calls the same method on every sink in order.
}
```

Each `Trace` interface method delegates to every sink. CLI uses `CompositeTrace(printTrace, debugTrace)` only when both `-v` and `--debug` are set; otherwise a single trace is used directly.

### Described mixin

Single small interface for components that carry prose:

```java
package io.guessit.engine;

public interface Described {
    /** One-line human-readable description of what this component does. */
    String description();
}
```

Applied to:

- `Extractor` — gains `default String description() { return name(); }` (fallback to `name()`)
- `PostProcessor` (the rule interface used by `PostPhase`) — same default
- `Marker` (the marker producer interface used by `MarkerPhase`) — same default

Each concrete implementation overrides with prose. ~34 extractors + post processors + markers ≈ 55 components touched. Default fallback ensures partial coverage during incremental rollout still compiles and runs.

### Sub-step instrumentation surface

Sub-steps emitted via `ctx.trace.subStep(...)` from these call sites:

1. **`PatternMatcher.regex(input, pattern, name, opts, trace)`** — new `Trace` parameter (overload, callers updated).
   - On entry: `subStep("Trying regex " + pattern.pattern())`
   - For each candidate before validator: `subStep("Considered '" + raw + "' at " + start + "-" + end + (accepted ? " — accepted" : " — rejected (validator)"))`
2. **`PatternMatcher.string(input, needles, name, opts, trace)`** — new overload too.
   - On entry: `subStep("Trying needles: " + summarise(needles))` (truncate at 6, append `, …`)
   - Per accepted match: `subStep("Considered '" + raw + "' at " + start + "-" + end + " — accepted")`
   - Rejected matches (failed validator or word-boundary): `subStep("Considered '" + raw + "' at " + idx + "-" + end + " — rejected (" + reason + ")")`
3. **`ConflictSolver.solve(matches, trace)`** — new `Trace` parameter; existing single-arg overload kept for non-tracing callers (Trace.NOOP). Per pair decision:
   - `subStep("Dropping " + loserSummary + " — overlaps " + winnerSummary + " (" + reason + ")")`
   - reason ∈ `{shorter span, lower priority, …}`
4. **Multi-stage processors** — those with internal phases (e.g. SeasonEpisode, Title, Hole detection): each stage emits its own `subStep("Stage N: …")` at the boundary.
5. **`OutputPhase`** — for each property assigned: `subStep("Set " + property + " ← " + value + " from match at " + start + "-" + end)`. For composed properties (title from holes), the source description is "from holes 0-10" or similar.
6. **`MarkerPhase`** — current `note` events stay. New per-marker `subStep("Found " + markerName + " marker '" + raw + "' at " + start + "-" + end)` triggered only when DebugTrace is attached (use trace.subStep, which PrintTrace already ignores).

### CLI behaviour

```
@Option(names = "--debug")            boolean debug;
@Option(names = "--debug-out", paramLabel = "PATH")  Path debugOut;
@Option(names = "--debug-markers")    boolean debugMarkers;
```

Wiring in `GuessitCli.call()`:

```java
Appendable debugSink = null;
DebugTrace debugTrace = null;
if (debugMarkers && !debug) {
    System.err.println("error: --debug-markers requires --debug");
    return 2;
}
if (debug) {
    debugSink = (debugOut != null)
        ? Files.newBufferedWriter(debugOut, StandardCharsets.UTF_8)
        : new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    debugTrace = new DebugTrace(debugSink, debugMarkers);
}

PrintTrace verboseTrace = verbose ? new PrintTrace(System.out) : null;

Trace trace;
if (verboseTrace != null && debugTrace != null) trace = new CompositeTrace(verboseTrace, debugTrace);
else if (verboseTrace != null)                  trace = verboseTrace;
else if (debugTrace  != null)                   trace = debugTrace;
else                                            trace = Trace.NOOP;
```

After all filenames processed, close `debugSink` if it was a file writer.

Behaviour matrix:

| Flags                    | stdout                | stderr  | Notes |
|--------------------------|-----------------------|---------|-------|
| (none)                   | plain formatter result|         | unchanged |
| `-v`                     | machine trace         |         | unchanged |
| `--debug`                | plain formatter result| prose   | structured output (`--json`/`--yaml`/`-P`) on stdout still allowed |
| `--debug --debug-out=F`  | plain formatter result|         | prose written to F |
| `--debug --debug-markers`| plain formatter result| prose + span views | span view redrawn on every change |
| `--debug-markers` alone  | error                 | error   | exit 2, message on stderr |
| `-v --debug`             | machine trace         | prose   | both, interleaved per event |
| `-v --json`              | machine trace + warning on stderr | warning | unchanged warning behaviour |
| `--debug --json`         | JSON                  | prose   | no warning — debug coexists with structured stdout |

`--debug` does NOT trigger the existing "json/yaml/show-property ignored" warning, because debug goes to a separate stream.

### Library API

`DebugTrace` and `CompositeTrace` are public in `io.guessit.engine`. Library consumers compose their own sinks and pass via existing `Guessit.guess(String input, Trace trace)` overload — no new entry point required.

## Sample output

`--debug` on `Movie.Name.2020.1080p.BluRay.x264-GRP.mkv` → stderr (or `--debug-out` path):

```
For: Movie.Name.2020.1080p.BluRay.x264-GRP.mkv

Marker phase — detecting path/group/container markers
  Found group marker '-GRP' at 32-36
  Found container marker '.mkv' at 36-40

Extractor phase — scanning input for property patterns
  Looking for year (4-digit 19xx/20xx)
    Trying regex \b(19|20)\d\d\b
    Considered '2020' at 11-15 — accepted
  Looking for screen_size (resolution: 480p/720p/1080p/2160p/4K)
    Trying needles: 1080p, 720p, 480p, 2160p, 4K
    Considered '1080p' at 16-21 — accepted
  Looking for source (BluRay, WEB-DL, HDTV, …)
    Considered 'BluRay' at 22-28 — accepted
  Looking for video_codec (x264, x265, h264, h265, xvid, …)
    Considered 'x264' at 29-33 — accepted
  Looking for release_group (trailing dash group token)
    Considered 'GRP' at 34-37 — accepted

Conflict phase — resolving overlapping matches
  No overlaps

Extractor post phase — refining matches after conflict resolution
  (no changes)

Post phase — running heuristic rules
  Running rule SeasonEpisode (extract SxxExx / 1x01 / season+episode)
    Stage 1: scanning for SxxExx tokens — none
    Stage 2: scanning for season-only tokens — none
  Running rule Title (derive title from leading hole)
    Stage 1: collecting holes outside matches — hole 0-11 found
    Stage 2: cleaning separators — 'Movie Name'

Output phase — assembling result
  Set title ← 'Movie Name' from holes 0-11
  Set year ← 2020 from match 11-15
  Set screen_size ← '1080p' from match 16-21
  Set source ← 'BluRay' from match 22-28
  Set video_codec ← 'x264' from match 29-33
  Set release_group ← 'GRP' from match 34-37
  Set container ← 'mkv' from marker 36-40

GuessIt found:
title: Movie Name
year: 2020
screen_size: 1080p
source: BluRay
video_codec: x264
release_group: GRP
container: mkv
```

## Edge cases

- **`--debug-out=PATH` with unwritable path** — `IOException` propagates out of `call()`, mapped to non-zero exit.
- **Multiple filenames + `--debug-out=FILE`** — single file, all filenames appended in order, separated by blank line (mirrors verbose behaviour).
- **`Trace.NOOP` callers of `PatternMatcher.regex/string`** — keep the old overload (no `Trace` param) delegating to the new one with `Trace.NOOP`, so non-extractor callers (if any in tests) compile unchanged.
- **Lambda-style processors** — same caveat as `-v`: the synthetic class name appears unless converted to a named class. Description fallback to `getClass().getSimpleName()` keeps behaviour compatible with `-v`.
- **Long needle lists** — truncate at 6 with `, …` suffix to keep output readable.
- **Pattern matcher recursive callers** — the `Trace` parameter must be threaded through every extractor that delegates to `PatternMatcher`. Plan should enumerate them so none is missed.

## Test strategy

Unit:

- `DebugTraceTest` — feed events directly, assert prose lines.
- `CompositeTraceTest` — verify fan-out order and that exceptions in one sink do not skip others.
- `PatternMatcherDebugTest` — assert regex/string scanners emit subStep events for try / accepted / rejected.
- `ConflictSolverDebugTest` — pair decisions emit prose with reason.
- Per-component description fallback: a ParameterizedTest over all `Extractor` / `PostProcessor` / `Marker` impls asserting `description()` is non-null and non-empty (catches missing overrides).

Integration:

- `DebugCliTest` — `--debug` alone, `--debug --json`, `--debug --debug-out=tmpfile` (assert file contents), `--debug` with multiple filenames.
- `DebugCombinedTest` — `-v --debug` produces both PrintTrace machine output on stdout AND DebugTrace prose on stderr.
- `VerboseCliTest` — must remain green (no -v format change).

## Files

New:

- `src/main/java/io/guessit/engine/DebugTrace.java`
- `src/main/java/io/guessit/engine/CompositeTrace.java`
- `src/main/java/io/guessit/engine/Described.java`
- `src/main/java/io/guessit/engine/SpanRenderer.java`
- `src/test/java/io/guessit/engine/DebugTraceTest.java`
- `src/test/java/io/guessit/engine/CompositeTraceTest.java`
- `src/test/java/io/guessit/engine/PatternMatcherDebugTest.java`
- `src/test/java/io/guessit/engine/ConflictSolverDebugTest.java`
- `src/test/java/io/guessit/engine/DescriptionsCoverageTest.java`
- `src/test/java/io/guessit/engine/SpanRendererTest.java`
- `src/test/java/io/guessit/cli/DebugCliTest.java`
- `src/test/java/io/guessit/cli/DebugCombinedTest.java`

Edited:

- `src/main/java/io/guessit/engine/Trace.java` (add `subStep`, 3-arg `step`, 2-arg `phase`, `spans`)
- `src/main/java/io/guessit/engine/TraceDiff.java` (new `emit(before, after, ParseContext)` overload that fires `spans` on change)
- `src/main/java/io/guessit/engine/PrintTrace.java` (no-op the new methods; delegate where sensible)
- `src/main/java/io/guessit/engine/Extractor.java` (add `description()` default)
- `src/main/java/io/guessit/engine/PatternMatcher.java` (add `Trace` parameter overloads)
- `src/main/java/io/guessit/engine/ConflictSolver.java` (add `Trace` parameter overload)
- `src/main/java/io/guessit/engine/ConflictPhase.java` (pass `ctx.trace` through)
- `src/main/java/io/guessit/engine/MarkerPhase.java` (`subStep` per marker; description on header)
- `src/main/java/io/guessit/engine/ExtractorPhase.java` (3-arg `step` with description)
- `src/main/java/io/guessit/engine/ExtractorPostPhase.java` (3-arg `step` with description)
- `src/main/java/io/guessit/engine/PostPhase.java` (3-arg `step` with description; processor description())
- `src/main/java/io/guessit/engine/OutputPhase.java` (`subStep` per assigned property)
- `src/main/java/io/guessit/cli/GuessitCli.java` (`--debug`, `--debug-out`, trace wiring)
- All ~34 extractors under `src/main/java/io/guessit/rules/property/` (description override)
- All processors under `src/main/java/io/guessit/rules/post/` (description override; multi-stage `subStep`s)
- All markers under `src/main/java/io/guessit/rules/markers/` (description override)
- All extractor call sites of `PatternMatcher.regex/string` (pass `ctx.trace`)
