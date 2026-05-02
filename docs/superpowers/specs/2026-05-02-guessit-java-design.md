# guessit-java — Feature-Equal Java Port of Python guessit

**Status:** approved design, awaiting plan
**Date:** 2026-05-02
**Source project:** `/tmp/guessit` (Python guessit + rebulk-based)
**Target:** `/home/finkel/work_self/guessit-java`

## Goal

Build a Java library + CLI that parses video filenames and release names into
structured metadata, **feature-equal** to the Python `guessit` package. All YML
fixture tests shipped with Python guessit (`guessit/test/*.yml`,
`guessit/test/rules/*.yml`) must pass against the Java implementation byte-for-byte
on the resulting property map.

## Non-goals

- Not a wire-compatible drop-in for the Python `guessit` Python module API
  (Java consumers get an idiomatic Java API; only the *output map* is equivalent).
- Not a rebulk Java port published as a standalone library — engine code lives
  inside `io.guessit.engine` for this project's needs only.
- No GUI, no daemon mode, no web service. CLI + library only.

## Constraints / decisions

| Decision | Choice | Rationale |
|---|---|---|
| Build tool / Java | Maven, Java 25 | Maven is dominant for JVM library distribution; Java 25 = latest |
| YML parity bar | 100% strict | User-stated goal; defines done |
| Engine approach | Idiomatic Java rewrite (not faithful Python port) | User preference; accepted longer iteration tail to reach parity |
| Regex layer | `java.util.regex` directly | No third-party regex engine needed |
| Result type | Java `record`s with `toMap()` view | Type-safe Java surface; map view drives YML compare |
| Test runner | Single `@ParameterizedTest` over discovered YML cases | User preference (Q5=b) |
| Config loading | Full parity — bundled defaults + XDG/`~/.guessit/` + `--config` | User preference (Q6=b) |
| CLI | picocli, full flag parity with Python `__main__.py` | User preference (Q7=a) |
| Lang/country data | Embedded CSV ported from babelfish + guessit overrides | Required for alias-driven YML cases |
| Dependencies | picocli, jackson-databind, snakeyaml, junit-jupiter (test) | Minimum needed |
| JPMS | Not used (plain classpath jar) | Avoids shaded-jar JPMS conflicts |

## Architecture

```
io.guessit
├── Guessit                  // entry point: Guessit.parse(name, opts)
├── GuessResult              // record + toMap()
├── Options                  // immutable record (config, type hint, expected_*, etc.)
├── cli.GuessitCli           // picocli-based main
├── config
│   ├── ConfigLoader         // bundled options.json + XDG/--config merge
│   └── OptionsConfig        // parsed config tree
├── lang
│   ├── Language             // record(alpha2, alpha3, name)
│   ├── Country              // record(alpha2, name)
│   └── LanguageRegistry     // CSV-backed lookup + alias maps
├── engine
│   ├── Match                // record
│   ├── MatchSet             // mutable collection w/ overlap queries
│   ├── Pipeline             // ordered phase runner
│   ├── Phase                // sealed interface: Markers, Extract, Conflicts, Post
│   └── PatternMatcher       // helpers around java.util.regex
└── rules
    ├── markers/{PathMarker, GroupMarker}
    ├── property/{Year, Date, Season, Episode, Source, VideoCodec, AudioCodec,
    │             ScreenSize, Container, ReleaseGroup, Title, EpisodeTitle,
    │             Language, Country, Edition, Other, Website, StreamingService,
    │             Bonus, Cd, Crc, Film, Part, Size, BitRate, MimeType, Type}
    └── post/{ConflictSolver, PrivateRemover, TypeInference, Output}
```

### Pipeline phases (deterministic order)

1. **Markers** — split input into path segments + bracket/paren groups.
2. **Extract** — each property class produces raw `Match`es into shared `MatchSet`.
3. **Conflict resolution** — overlap winner by per-property priority
   (replicates rebulk's `ConflictSolver`).
4. **Post** — Title from longest unmatched gap, EpisodeTitle from trailing gap,
   type inference (movie/episode), private match cleanup.
5. **Output** — assemble `GuessResult`.

Rules compose via fixed-order registry (`Rules.allInOrder()`).

### Engine contract

```java
public sealed interface Phase permits MarkerPhase, ExtractorPhase, ConflictPhase, PostPhase, OutputPhase {
    void apply(ParseContext ctx);
}

public final class ParseContext {
    final String input;
    final Options options;
    final OptionsConfig config;
    final MatchSet matches;          // mutable
    final List<Marker> markers;      // path segments + groups
    GuessResult result;              // built in OutputPhase
}

public interface Extractor {
    String name();                   // property key
    int priority();                  // default 1000; higher wins overlap
    void extract(ParseContext ctx);
    default void postProcess(ParseContext ctx) {}
}

public final class MatchSet {
    Stream<Match> all();
    Stream<Match> named(String name);
    Stream<Match> overlapping(int start, int end);
    Stream<Match> inMarker(Marker m);
    void add(Match m);
    void remove(Match m);
    void replace(Match old, Match neu);
}

public final class PatternMatcher {
    static List<Match> regex(String input, Pattern p, String name, RegexOpts opts);
    static List<Match> string(String input, Set<String> needles, String name, StringOpts opts);
}
```

### Conflict resolution

1. Group all matches by overlap.
2. Within group, sort by `(priority desc, length desc, start asc)`.
3. Keep winner; drop losers unless tagged `coexist`.
4. Special-case rules (e.g., year vs episode digits) hooked via per-extractor `postProcess`.

### Title / EpisodeTitle

Built in dedicated post phases reading remaining unmatched gaps in the **title
marker** — the path segment chosen for title extraction (last segment of the
input path that contains identifying matches; falls back to whole input when no
path separators present, matching Python's `path.py` behavior). Title = first
significant gap in the title marker; EpisodeTitle = trailing gap after the
last season/episode match within the same marker.

### Type inference

- has season/episode → `episode`
- has year + no season → `movie`
- title only → fallback to `Options.type` or `movie`

## Data model

```java
public record GuessResult(
    String title,
    String alternativeTitle,
    Integer year,
    LocalDate date,
    Integer season, List<Integer> seasonList,
    Integer episode, List<Integer> episodeList,
    Integer episodeCount, Integer seasonCount,
    String episodeTitle,
    String episodeFormat,
    String type,                 // "movie" | "episode"
    List<Language> language, List<Language> subtitleLanguage,
    List<Country> country,
    String source, List<String> other,
    List<String> videoCodec, List<String> audioCodec,
    List<String> audioChannels, List<String> audioProfile,
    List<String> videoProfile, List<String> videoApi,
    String screenSize, String aspectRatio, Integer frameRate,
    Quantity bitRate, Quantity size,
    String container, String mimetype,
    String releaseGroup, String streamingService, String website,
    String edition, Integer cd, Integer cdCount,
    Integer part, Integer version, Integer film, String filmTitle,
    Integer bonus, String bonusTitle, String crc32,
    Map<String,Object> extras
) {
    public Map<String,Object> toMap();
    public String toJson();
    public String toYaml();
}

public record Options(
    String type,
    String name,
    List<String> expectedTitle,
    List<String> expectedGroup,
    List<String> excludes,
    List<String> includes,
    Boolean enforceListWhenSingle,
    Path configPath,
    boolean noUserConfig,
    boolean noDefaultConfig,
    boolean advancedConfigMerged,
    Map<String,Object> raw
) {
    public static Builder builder();
}

public record Language(String alpha2, String alpha3, String name) {}
public record Country(String alpha2, String name) {}
public record Quantity(double value, String unit) { public String format(); }

record Match(
    String name, Object value, int start, int end, String raw,
    int priority, Set<String> tags, boolean isPrivate
) {}
```

Notes:
- Nullable scalars used over `Optional<T>` for record component clarity.
- `toMap()` mirrors Python guessit: list collapse for single-value properties unless
  `enforceListWhenSingle`, drop nulls.
- `Quantity` keeps unit because YML expects e.g. `1.5 Mbps`, `4.7 GB`.
- `extras` covers rare/dynamic Python keys to preserve test parity without hardcoding.

## Configuration + bundled data

Bundled resources at `src/main/resources/io/guessit/`:

```
config/options.json              // verbatim copy from /tmp/guessit/guessit/config/options.json
data/tlds-alpha-by-domain.txt    // verbatim copy
data/iso-639.csv                 // alpha2,alpha3,name,aliases (ported from babelfish)
data/iso-3166-1.csv              // alpha2,name,aliases
data/scripts.csv                 // ISO 15924 script codes
data/lang-aliases.csv            // guessit-specific overrides (e.g. "vo"→Original Version)
data/country-aliases.csv         // guessit-specific overrides
```

### ConfigLoader resolution order (matches Python)

1. Bundled `options.json` from classpath (unless `noDefaultConfig`).
2. `${XDG_CONFIG_HOME:-~/.config}/guessit/options.{json,yml,yaml}` (unless `noUserConfig`).
3. `~/.guessit/options.{json,yml,yaml}` (unless `noUserConfig`).
4. Explicit `--config <path>` (repeatable).
5. Programmatic `Options.raw` overlay.

Each layer deep-merges into next using same merge rules as Python's
`options.py:merge_options`: lists concat, dicts merge, scalars override.

### LanguageRegistry

```java
public final class LanguageRegistry {
    public static LanguageRegistry instance();           // singleton, lazy CSV load
    public Optional<Language> find(String token);        // tries name, alpha2, alpha3, alias (case-insensitive)
    public Optional<Country>  findCountry(String token);
    public Optional<String>   findScript(String token);
}
```

Quirks to preserve (from Python `language.py`):
- `"vo"` → name="Original Version", no ISO mapping
- `"sub"` → triggers subtitle_language treatment, not language
- Multi-token names: `"brazilian portuguese"` → Portuguese + country=BR
- Synonyms: `"english"`/`"eng"`/`"en"` all map to same Language

CSV format chosen over JSON for byte-efficient embedded data + line-oriented diff.

## Public API

```java
public final class Guessit {
    // one-shot
    public static GuessResult parse(String input);
    public static GuessResult parse(String input, Options options);

    // reusable (caches config + compiled patterns)
    public static Guessit withOptions(Options options);
    public GuessResult guess(String input);

    // introspection
    public Map<String, List<Object>> properties();
    public List<String> suggestedExpected(Collection<String> titles);
}
```

## CLI

`io.guessit.cli.GuessitCli`, picocli, full flag parity with Python `__main__.py`:

```
guessit-java [OPTIONS] FILENAME...

  -t, --type=TYPE             movie|episode hint
  -n, --name=NAME             override input name
  -Y, --date-year-first
  -D, --date-day-first
  -L, --allowed-language=L    repeatable
  -C, --allowed-country=C     repeatable
  -E, --episode-prefer-number
  -T, --expected-title=T      repeatable
  -G, --expected-group=G      repeatable
      --excludes=P            repeatable
      --includes=P            repeatable
  -c, --config=FILE           repeatable; overrides defaults
      --no-user-config
      --no-default-config
  -j, --json
  -y, --yaml
  -v, --verbose
  -P, --show-property=NAME    print only that property
      --advanced              show advanced match data
  -V, --version
  -h, --help
```

Output formats:
- default: human-readable `key: value` lines (mirrors Python default)
- `--json`: Jackson, stable key order
- `--yaml`: SnakeYAML

## Build (Maven `pom.xml`)

- `maven-shade-plugin` produces `guessit-java-<ver>-cli.jar` with picocli + Jackson + SnakeYAML inlined.
- Main artifact `guessit-java-<ver>.jar` library-only (deps declared, not shaded).
- Manifest `Main-Class: io.guessit.cli.GuessitCli` on shaded jar only.
- No JPMS module-info.

Dependencies:

| Coordinate | Scope |
|---|---|
| `info.picocli:picocli:4.7.x` | compile (CLI module) |
| `com.fasterxml.jackson.core:jackson-databind:2.18.x` | compile |
| `org.yaml:snakeyaml:2.x` | compile |
| `org.junit.jupiter:junit-jupiter:5.11.x` | test |

## Testing

### YML parity (primary target — 100%)

Layout `src/test/resources/yml/` mirrors Python `guessit/test/`:

```
movies.yml, episodes.yml, various.yml, streaming_services.yaml,
enable_disable_properties.yml, rules/*.yml
```

Files copied verbatim from `/tmp/guessit/guessit/test/` — never edit. New cases
land upstream first then re-sync.

### YML format parser (`YmlTestLoader`)

Custom mini-parser, not raw SnakeYAML (Python guessit uses custom YAML loader in
`yamlutils.py`). Format quirks:

- Multiple `?` lines preceding one `:` block → all share expected output
- `?` line starting with `-` → input that should NOT match given results (negative)
- `: ~` or empty `:` → expect empty result
- Inline `options:` key inside expected block → per-case Options override
- Top-level `__default__:` block → defaults applied to every case in file

Unit-test the loader against small fixtures before plugging into parity suite.

### Parameterized parity test

```java
@ParameterizedTest(name = "{0}")
@MethodSource("allYmlCases")
void ymlParity(YmlCase c) {
    var result = Guessit.parse(c.input(), c.options()).toMap();
    if (c.negative()) {
        assertNotEquals(c.expected(), result);
    } else {
        assertEquals(c.expected(), result);
    }
}

static Stream<YmlCase> allYmlCases() {
    return YmlTestLoader.discoverAll("yml/");
}
```

Test name `{0}` → `YmlCase.toString()` returns `<file>:<line> "<input>"` for
traceable failures. Surefire prints failing case names; CI artifact uploads JUnit XML.

### Unit tests

- `engine/`: MatchSet overlap queries, ConflictSolver tie-break, PatternMatcher helpers
- `lang/`: LanguageRegistry alias resolution edge cases
- `config/`: ConfigLoader merge order, XDG resolution, JSON/YAML format equivalence
- `cli/`: picocli arg parsing, output formatter (JSON, YAML, plain)
- Each rule package: small focused tests for tricky branches

### Test execution staging

Manage 100% parity grind across phases. Each phase commits passing subset; rest
marked `@Disabled("phase-N")` so intermediate commits stay green:

| Phase | Adds | Cumulative YML pass target |
|---|---|---|
| 1 | engine + year, screen_size, video_codec, audio_codec, container | ~20% |
| 2 | markers, source, release_group, language, country | ~50% |
| 3 | episode/season/date | ~80% |
| 4 | title + episode_title | ~95% |
| 5 | long-tail edge cases | 100% |

Final commit removes all `@Disabled` tags.

### No mocks

Engine is pure, deterministic, fast (no I/O at parse time after config load).

## Risks

1. **Idiomatic Java rewrite vs 100% YML parity** — chosen approach diverges from
   Python execution model. Mitigated by phased staging + Python source remaining
   available at `/tmp/guessit` for behavior cross-checks during debugging.
2. **Custom YAML test format** — must reproduce Python `yamlutils.py` semantics
   exactly. Mitigated by unit-testing loader on every quirk before parity suite runs.
3. **Babelfish data fidelity** — alias coverage drives many YML cases. Mitigated
   by porting CSVs directly from babelfish source + maintaining an explicit
   `lang-aliases.csv` overlay for guessit-specific entries.
4. **Pattern ordering quirks** — rebulk's stable conflict resolution depends on
   insertion order. Mitigated by `Rules.allInOrder()` registry + matching
   per-rule priority constants from Python source.

## Open items

None at design time. Ambiguities to be resolved during implementation:
- Exact priority constants per rule will be lifted from Python source during
  Phase 1 implementation (each `Rebulk.regex(...).priority(N)` call in Python =
  `priority(N)` constant in Java).
- `Quantity.format()` rounding rules will be confirmed against YML fixtures
  during Phase 1 (likely 1 decimal place, unit suffix preserved).

## Post-Plan-5 polish (deferred)

- **Refactor regex patterns to [Sift](https://github.com/Mirkoddd/Sift)** —
  fluent type-safe DSL on top of `java.util.regex.Pattern` (also supports RE2J
  for ReDoS safety). Defer until 100% YML parity is hit so debugging diff vs
  Python regex strings stays straightforward. Once stable, translate ~50-75
  patterns across rules to Sift DSL for readability + compile-time validation.
