# Verbose Mode for guessit-java CLI

**Status:** approved design, awaiting plan
**Date:** 2026-05-05
**Related:** parity goal in `2026-05-02-guessit-java-design.md`

## Goal

Implement `-v` / `--verbose` flag on `GuessitCli`. When set, emit a structured
trace of the parsing pipeline to **stdout**, mirroring the spirit of Python
`guessit -v` (debugging aid for parity work) using Java-native pipeline
concepts (Phase / Extractor / PostProcessor) rather than rebulk internals.

## Non-goals

- Byte-exact match with Python `guessit -v` output. Internals differ; only the
  shape (per-pattern / per-rule events) is preserved.
- Library-facing verbose API. The new `Guessit.guess(String, Trace)` overload
  is public (Java visibility) but not advertised in README; CLI is the
  only documented consumer.
- Conflict-pair "X removed in favor of Y" reason strings. `ConflictSolver`
  internals are not instrumented; conflict removals appear at the phase level
  only.
- Programmatic capture / structured (JSON) trace. Plain text only.

## Architecture

### Trace API

Two new types in `io.guessit.engine`:

```java
public interface Trace {
    Trace NOOP = new Trace() {};
    default void input(String s) {}
    default void phase(String name) {}
    default void step(String kind, String name) {}   // kind: marker|extract|post|rule
    default void added(Match m) {}
    default void removed(Match m) {}
    default void note(String msg) {}
    default void result(GuessResult r) {}
}

public final class PrintTrace implements Trace {
    public PrintTrace(Appendable out) { ... }
    // implements every method, writing formatted lines to `out`
}
```

`Trace.NOOP` is the default sink — instrumentation calls in phases compile and
run to no-ops when verbose is not enabled.

### ParseContext extension

```java
public Trace trace = Trace.NOOP;
```

Public mutable field, set by `Guessit.guess(String, Trace)` before
`pipeline.run(ctx)` is invoked. No `Options` change.

### Guessit entry point

New public overload:

```java
public GuessResult guess(String input, Trace trace) {
    var ctx = new ParseContext(input, options, config);
    ctx.trace = trace;
    trace.input(input);
    pipeline.run(ctx);
    trace.result(ctx.result);
    return ctx.result;
}
```

Existing `guess(String)` delegates to `guess(input, Trace.NOOP)` so behaviour
is unchanged when no trace is requested.

### Phase instrumentation

Each phase emits a `phase()` header on entry and uses snapshot-diff around
each inner unit to derive `added` / `removed` events. Snapshot-diff means:

```java
var before = ctx.matches.snapshot();
unit.run(ctx);
var after = ctx.matches.snapshot();
// diff(before, after, ctx.trace) → emits added / removed events via Match equals
```

The `diff` helper is a package-private static method in `io.guessit.engine`
(e.g. `TraceDiff.emit(before, after, trace)`), used by every phase that
needs it. Keeps phase classes free of inline diff logic.

Per-phase mapping:

| Phase | Header | Inner step events | Diff scope |
|---|---|---|---|
| `MarkerPhase` | `markers` | one `note` per marker found, formatted `<raw>:(<start>,<end>)+name=<marker.name>` | n/a (markers, not matches) |
| `ExtractorPhase` | `extractors` | `step("extract", e.name())` per extractor | per extractor |
| `ConflictPhase` | `conflicts` | none | whole-phase diff (solver removes; we report removals) |
| `ExtractorPostPhase` | `extractor_post` | `step("post", e.name())` per extractor | per extractor |
| `PostPhase` | `post` | `step("rule", proc.getClass().getSimpleName())` per processor | per processor |
| `OutputPhase` | `output` | none | n/a |

Lambda-based `PostProcessor` instances (if any) report as the lambda's
synthetic class name; this is acceptable since current processors are all
dedicated classes. If a future lambda processor needs a clean name, the fix
is to convert it to a class.

### Match printing format

```
<raw>:(<start>,<end>)[+private]+name=<name>[+priority=<p>][+tags=[t1,t2,...]]
```

- `+priority=` shown only when `priority != 1000` (default).
- `+tags=` shown only when tags are non-empty; tags joined by `,` in
  ASCII-sorted order (deterministic across runs because `Match.tags()`
  storage is an unordered immutable `Set`), wrapped in `[]`.
- `+private` shown only when `isPrivate` is true.

Examples:

```
2020:(11,15)+name=year
20:(11,13)+name=season+tags=[weak-duplicate,weak-episode]
2020:(11,15)+private+name=weak_duplicate+tags=[weak-duplicate,weak-episode]
```

### Output layout

Whole-document shape produced by `PrintTrace`:

```
For: <input>

[phase] markers
  marker: <marker rendered as match-like span>
[phase] extractors
  [extract] <extractor.name()>
    + <match>
    + <match>
  [extract] <extractor.name()>
    (no changes)
[phase] conflicts
  - <match>
  - <match>
[phase] extractor_post
  [post] <extractor.name()>
    - <match>
    + <match>
[phase] post
  [rule] <ProcessorClass>
    - <match>
[phase] output

GuessIt found:
<plain formatter output>
```

Indentation: two spaces per nesting level. `(no changes)` is emitted when a
step's diff is empty, to signal that the step ran. Header lines are not
indented; step lines indent by 2; match lines indent by 4. Final
`GuessIt found:` block re-uses the existing `PlainFormatter.format(result)`
for the body.

### CLI behaviour

`GuessitCli.call()` adjustments when `verbose` is true:

- Instantiate one `PrintTrace(System.out)` outside the filename loop.
- For each filename, call `guessit.guess(fn, trace)`.
- Suppress the per-filename formatted-output print path (`json`, `yaml`,
  `showProperty`, `PlainFormatter`) — `trace.result(...)` already prints the
  final block.
- For multiple filenames, emit one blank line between trace blocks.

When `--verbose` is combined with `--json`, `--yaml`, or `-P/--show-property`:

- Print one warning line to **stderr**:
  `warning: --json/--yaml/--show-property ignored when --verbose is set`
- Continue with verbose-only output. Exit code unchanged.

## Edge cases

- **Empty input** — pipeline still runs; trace shows phase headers with no
  matches; `GuessIt found:` block prints whatever the empty-input pipeline
  produces. No crash.
- **Match with multi-line raw** — raw substring printed as-is. Newlines in
  release names extremely rare; not specially escaped.
- **Replace operations** (`MatchSet.replace(old, new)`) — diff sees one
  removal and one addition; reported as such. Acceptable; not worth
  distinguishing from independent remove+add for a debug trace.
- **Diff equality** — `Match` is a record; equality is value-based across
  all fields. Two different additions of structurally-identical matches
  would not be reported as both "added"; in practice this does not occur,
  and if it did, the trace under-reporting is harmless.

## Test strategy

Unit:

- `PrintTraceTest` — feed `Trace` events directly, assert formatted lines
  byte-for-byte.
- `MatchFormatTest` — table of match shapes (with/without priority, tags,
  private) → expected formatted string.

Integration:

- `VerboseCliTest` — run CLI in-process on a known input
  (`Movie.Name.2020.1080p.BluRay.x264-GRP.mkv`), capture stdout, assert it
  contains `For:`, `[phase] extractors`, `[extract] year`,
  `+ 2020:(11,15)+name=year`, `[phase] post`, `GuessIt found:`.
- `NormalOutputUnchangedTest` — running the existing CLI test suite without
  `-v` produces output identical to before this change. Achieved by not
  altering any pre-existing code path; only adding the new branch.

## Files

New:

- `src/main/java/io/guessit/engine/Trace.java`
- `src/main/java/io/guessit/engine/PrintTrace.java`
- `src/main/java/io/guessit/engine/TraceDiff.java` (package-private diff helper)
- `src/test/java/io/guessit/engine/PrintTraceTest.java`
- `src/test/java/io/guessit/cli/VerboseCliTest.java`

Edited:

- `src/main/java/io/guessit/engine/ParseContext.java` (+1 field)
- `src/main/java/io/guessit/engine/MarkerPhase.java`
- `src/main/java/io/guessit/engine/ExtractorPhase.java`
- `src/main/java/io/guessit/engine/ConflictPhase.java`
- `src/main/java/io/guessit/engine/ExtractorPostPhase.java`
- `src/main/java/io/guessit/engine/PostPhase.java`
- `src/main/java/io/guessit/engine/OutputPhase.java`
- `src/main/java/io/guessit/Guessit.java` (+1 overload)
- `src/main/java/io/guessit/cli/GuessitCli.java`
