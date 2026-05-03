# Architecture: Phases, Rules, Extractors

This document explains the parsing engine: the fixed pipeline of **phases** the
input flows through, the **rules** registry that composes them, and the
**extractors** that produce each property.

For high-level design context and the parity goal, see
`docs/superpowers/specs/2026-05-02-guessit-java-design.md`.

## Big picture

`Guessit.parse(name, opts)` builds a `ParseContext` and runs a `Pipeline` of
`Phase`s over it. Each phase mutates the shared context — adding markers,
adding/removing matches, building the final result. The pipeline is built once
by `Rules.defaultPipeline()` and is deterministic: same input → same output.

```
input string
    │
    ▼
┌──────────────────────────────────────────────────────┐
│ ParseContext (input, options, config, markers, MatchSet, result) │
└──────────────────────────────────────────────────────┘
    │
    ▼  Pipeline.run(ctx) — phases applied in fixed order:
    │
    1. MarkerPhase           split path + bracket/paren groups
    2. ExtractorPhase        each Extractor.extract() adds Matches
    3. ConflictPhase         resolve overlapping Matches
    4. ExtractorPostPhase    each Extractor.postProcess() refines/renames
    5. PostPhase             cross-cutting cleanup (paths, private, title marker)
    6. OutputPhase           assemble GuessResult into ctx.result
```

The pipeline is wired in `io.guessit.rules.Rules.defaultPipeline()` and the
phase types are a sealed interface at `io.guessit.engine.Phase`.

## Why this shape

The design mirrors Python `guessit` (rebulk-based) but is reimplemented as
idiomatic Java. The split into discrete phases exists for three reasons:

- **Determinism and debuggability.** Phases run in a fixed order, so a given
  input always produces the same intermediate state at each step. You can
  inspect `ctx.matches` between phases to localise a bug to one phase.
- **Separation of concerns.** Extraction (find candidate properties), conflict
  resolution (resolve overlaps), and post-processing (rename, drop, infer)
  have different invariants. Conflating them produced order-dependent bugs in
  earlier prototypes.
- **YML parity.** Python `guessit` runs rebulk processors in a specific order
  (markers → rules → conflict solver → post-processors). Matching that order
  is what lets the YML fixture suite pass byte-for-byte against this port.

## Core types

These types live in `io.guessit.engine` and are the shared vocabulary for all
phases and extractors.

| Type | Purpose |
|---|---|
| `ParseContext` | Mutable per-parse state: input, options, config, markers, `MatchSet`, output builder |
| `Marker` (record) | A named span in the input — `whole`, `path`, or `group` |
| `Match` (record) | A single extracted property occurrence: name, value, span, raw text, priority, tags, private flag |
| `MatchSet` | Mutable list of `Match` with overlap/marker queries |
| `Extractor` (interface) | Produces matches in `extract()` and optionally refines them in `postProcess()` |
| `Phase` (sealed) | One pipeline stage; six implementations, one per phase below |
| `Pipeline` | Holds an ordered list of `Phase` and runs them |

`Match` carries two metadata channels worth knowing:

- **`isPrivate`** — match exists only to influence other extractors (e.g. as
  an anchor or guard) and must not appear in the final output. `PrivateRemover`
  drops these in the post phase.
- **`tags`** — string flags read by other rules. Notable tags:
  - `"coexist"` — opt out of conflict resolution; this match is allowed to
    overlap others without being dropped.
  - `"SxxExx"` — set by `SeasonEpisodeExtractor` to mark canonical episode
    matches; `AbsoluteEpisodeRule` reads this to decide which leading numerics
    are actually absolute episodes.

## Phases

All phases implement `Phase.apply(ParseContext ctx)`. They are listed here in
the order `Rules.defaultPipeline()` runs them.

### 1. MarkerPhase — `engine/MarkerPhase.java`

**What:** runs each registered `MarkerProducer` to populate `ctx.markers`.

**Why first:** subsequent extractors and post-processors need to know where
path boundaries and bracketed groups are — both for scoping (a year inside
the last path segment matters more than one in a parent directory) and for
release-group detection (often the trailing bracketed group).

**Default producers:**

- `PathMarker` — emits a `whole` marker over the entire input plus one `path`
  marker per `/` or `\` separated segment. Used for per-filepart scoping.
- `GroupMarker` — emits a `group` marker for each balanced `()`, `[]`, or
  `{}` pair longer than two characters. Used by release-group, language, and
  other extractors to find content meant to be grouped together.

### 2. ExtractorPhase — `engine/ExtractorPhase.java`

**What:** calls `extract()` on each `Extractor` in registry order. Each
extractor adds `Match`es to `ctx.matches`.

**Why a separate phase:** all extractors run before any conflict resolution
so that conflict decisions can compare every candidate against every other.
If extractors resolved their own conflicts inline, ordering would matter and
late-registered rules could not influence earlier ones.

**Order matters even within this phase** because some extractors look at
matches produced by earlier ones (e.g. `SeasonEpisodeExtractor` sets the
`SxxExx` tag that `AbsoluteEpisodeRule.postProcess` later relies on). The
order is fixed in `Rules.allInOrder()`.

### 3. ConflictPhase — `engine/ConflictPhase.java`, `ConflictSolver.java`

**What:** resolves overlapping non-private matches by replicating Python's
`_default_conflict_solver`. For each pair of overlapping matches, the longer
span wins; on equal lengths, higher `priority` wins; otherwise both are kept.
Matches tagged `coexist` are exempt.

**Why between extract and post:** post-processors often rename or repartition
matches (e.g. promoting weak episodes to absolute episodes). They must run on
a deconflicted set, otherwise dropped candidates would still be visible to
the rename logic and the output would diverge from Python.

### 4. ExtractorPostPhase — `engine/ExtractorPostPhase.java`

**What:** calls `postProcess()` on each `Extractor` in registry order. The
default implementation is a no-op; extractors override it when they need a
second pass that sees the deconflicted match set.

**Why separate from `extract`:** lets a single extractor split its work into
"add candidates" (before conflict resolution) and "refine survivors" (after).
Examples in this codebase:

- `YearExtractor.postProcess` keeps only the year inside a bracketed group
  when multiple year candidates survive in one filepart (mirrors Python's
  `KeepMarkedYearInFilepart`).
- `AbsoluteEpisodeRule.postProcess` renames leading numeric episode matches
  to `absolute_episode` when an `SxxExx`-tagged episode also exists in the
  same filepart.

### 5. PostPhase — `engine/PostPhase.java`

**What:** runs cross-cutting `PostProcessor`s that are not tied to a single
property. Default processors:

- `PreferLastPath` — when multiple path segments produced matches of the
  same name, drop earlier-segment matches in favour of the last segment
  (the actual filename usually wins over parent directories).
- `PrivateRemover` — drops every match marked `isPrivate`. Private matches
  exist only as scaffolding for other rules; they must not leak into output.
- `TitleMarkerSelector` — picks the path segment with the most matches as
  `ctx.titleMarker`; the title is later derived from unmatched gaps inside
  that marker.

**Why after extractor post-processing:** these processors assume each
extractor has finished refining its own matches. `PreferLastPath`, for
example, would otherwise drop matches that an extractor's `postProcess` was
about to rename or relocate.

### 6. OutputPhase — `engine/OutputPhase.java`

**What:** runs a single assembler (`OutputBuilder` by default) that groups
the surviving matches by `name`, maps each group onto the corresponding
`GuessResult` field via the `GuessResultBuilder`, and writes the final result
to `ctx.result`. Unknown names land in an `extras` map.

**Why last and isolated:** keeps all type coercion (string → int, raw value
→ `Language` / `Country` / `Quantity` / `LocalDate`) and field-shape decisions
(scalar vs list — e.g. one episode vs `[1, 2]`) in one place, decoupled from
the extractors that produced the values.

## Rules

`io.guessit.rules.Rules` is the **composition layer**: a small static class
that wires the engine into a working parser by listing which markers,
extractors, and post-processors run, and in what order.

It exposes two factory methods:

- `Rules.defaultPipeline()` — returns the six-phase `List<Phase>` consumed by
  `new Pipeline(...)`. This is what `Guessit.parse` uses.
- `Rules.allInOrder()` — returns the canonical extractor list in registry
  order. Used both by `defaultPipeline()` (to feed `ExtractorPhase` and
  `ExtractorPostPhase` from a single source of truth) and by tests.

### Why a registry instead of annotations or autodiscovery

- **Order is part of the contract.** Some extractors depend on tags or
  matches produced by earlier ones. A simple ordered list makes the dependency
  visible at the call site instead of hidden in metadata.
- **Parity testing.** YML fixture parity requires exactly this set of rules
  in exactly this order. A flat list is the simplest way to guarantee that.
- **No hidden state.** Adding or removing an extractor is a single edit to
  `Rules.allInOrder()`; the change is reviewable in one diff.

### Rule sub-packages

| Package | Contents | Role |
|---|---|---|
| `rules.markers` | `PathMarker`, `GroupMarker` | Produce markers in MarkerPhase |
| `rules.property` | All `Extractor` implementations (one per property) | Produce/refine matches in ExtractorPhase + ExtractorPostPhase |
| `rules.post` | `PreferLastPath`, `PrivateRemover`, `TitleMarkerSelector`, `OutputBuilder` | Cross-cutting processors in PostPhase + OutputPhase |

## Extractors

An extractor is the unit of property recognition. It implements
`io.guessit.engine.Extractor`:

```java
public interface Extractor {
    String name();                              // property key, e.g. "year"
    default int priority() { return 1000; }     // tie-breaker in conflict resolution
    void extract(ParseContext ctx);             // pass 1: add candidates
    default void postProcess(ParseContext ctx) {}  // pass 2: refine after conflict
}
```

### Why two passes

The split between `extract` and `postProcess` exists so that an extractor can
look at the **deconflicted** match set before deciding on its final output.
Many guessit rules need this: keeping the year inside a bracketed group only
makes sense once you know which year candidates survived; promoting leading
digits to `absolute_episode` only makes sense once you know an `SxxExx`
episode survived. Doing both in `extract` would either fight the conflict
solver or duplicate it.

### How most extractors are built

The engine offers two helpers in `engine/PatternMatcher.java`:

- `PatternMatcher.regex(input, Pattern, name, RegexOpts)` — runs a regex,
  applies a value mapper and validator, returns `Match`es.
- `PatternMatcher.string(input, needles, name, StringOpts)` — runs a fixed
  set of literal needles with case/separator handling.

Together with `Validators` (separator-surround checks, numeric ranges) and
`Seps` (separator character class), these cover the bulk of extractors.
Example, the entirety of `YearExtractor.extract`:

```java
var opts = RegexOpts.defaults()
    .withValue(Integer::valueOf)
    .withValidator(m -> {
        if (!Validators.sepsSurround(input).test(m)) return false;
        int v = (Integer) m.value();
        return 1920 <= v && v < 2030;
    });
for (var match : PatternMatcher.regex(input, PATTERN, "year", opts)) {
    ctx.matches.add(match);
}
```

More involved extractors (e.g. `SeasonEpisodeExtractor`,
`ReleaseGroupExtractor`, `OtherExtractor`) drop down to direct regex /
manual scanning when a single helper call cannot express the rule.

### Priority and conflict behaviour

`priority()` is consulted **only as a tie-breaker** when two overlapping
matches have equal span length. Most extractors keep the default `1000` and
rely on length to win conflicts naturally. Lower values are used for
weak/ambiguous matches that should yield to anything more specific:

| Extractor | priority | Why |
|---|---|---|
| `WebsiteExtractor` | 100 | Domain-like strings often overlap titles; yield easily |
| `AbsoluteEpisodeRule` | 600 | Conceptual rule, not a real candidate producer |
| `WeakDuplicateExtractor` | 700 | Heuristic dedupe; lose to anything stronger |
| `WeakEpisodeExtractor` | 800 | Bare digits as episodes; lose to SxxExx-style matches |
| everything else | 1000 (default) | Standard extractors |

### Registered extractors (in `Rules.allInOrder()` order)

| # | Extractor | Property name | What it produces |
|---|---|---|---|
| 1 | `YearExtractor` | `year` | 4-digit year 1920–2029, surrounded by separators |
| 2 | `ScreenSizeExtractor` | `screen_size` | `720p`, `1080p`, `4K`, etc. |
| 3 | `VideoCodecExtractor` | `video_codec` (+ profile/api fields) | H.264/HEVC/etc. with profile and API tags |
| 4 | `AudioCodecExtractor` | `audio_codec` (+ channels/profile) | AC3/DTS/AAC etc. with channel layout |
| 5 | `ContainerExtractor` | `container` | File extensions: mkv, mp4, avi, … |
| 6 | `OtherExtractor` | `other` | Catch-all flags: PROPER, REPACK, INTERNAL, REMUX, … |
| 7 | `LanguageExtractor` | `language`, `subtitle_language` | Language tokens via `LanguageRegistry` |
| 8 | `CountryExtractor` | `country` | ISO country codes |
| 9 | `StreamingServiceExtractor` | `streaming_service` | NF, AMZN, HULU, … |
| 10 | `SourceExtractor` | `source` | BluRay, WEB-DL, HDTV, … |
| 11 | `WebsiteExtractor` | `website` | Domain-like prefixes; low priority |
| 12 | `EpisodeDetailsExtractor` | `episode_details` | Special, Pilot, Final, … |
| 13 | `EpisodeFormatExtractor` | `episode_format` | "Episode", "Ep", … |
| 14 | `VersionExtractor` | `version` | v2, v3 release versions |
| 15 | `AbsoluteEpisodeRule` | `absolute_episode` | Renames weak leading numerics in `postProcess` |
| 16 | `SeasonEpisodeExtractor` | `season`, `episode` | S01E02, 1x02, etc. (tags `SxxExx`) |
| 17 | `EpisodeWordExtractor` | `episode` | "Episode 5"-style word forms |
| 18 | `WeakEpisodeExtractor` | `episode` | Bare numerics as fallback episodes (priority 800) |
| 19 | `WeakDuplicateExtractor` | `weak_duplicate` | Dedupe scaffolding (priority 700) |
| 20 | `DiscRule` | `cd`, `cd_count` | Disc / CD numbering |
| 21 | `DateExtractor` | `date` | Calendar dates |
| 22 | `WeekExtractor` | `week` | ISO week numbers |
| 23 | `ReleaseGroupExtractor` | `release_group` | Trailing bracketed/dashed group name |

Order rationale: strong, low-ambiguity extractors run first so their matches
are present in `ctx.matches` when later, weaker ones make scoping decisions.
Conflict resolution (phase 3) then prunes overlaps; extractor `postProcess`
hooks (phase 4) refine the survivors.

### Adding a new extractor

1. Implement `Extractor` in `io.guessit.rules.property`. Use `PatternMatcher`
   helpers where possible. Pick a `name()` that matches the desired
   `GuessResult` field (or land in `extras` via the default `OutputBuilder`).
2. Decide priority: leave at `1000` unless the matches are intentionally weak.
3. Insert it in `Rules.allInOrder()` at the position that satisfies its
   dependencies on other extractors' tags or matches.
4. If the property maps to a typed field on `GuessResult`, add a case to
   `OutputBuilder` so the value is coerced and assigned. Otherwise it appears
   under `extras`.
5. Add YML fixtures and run the parity test.
