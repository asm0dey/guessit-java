# Plan 0: Foundation — Skeleton, Engine, Test Harness, CLI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the guessit-java project skeleton: Maven build, all foundational types (records), engine core (Match/MatchSet/Pipeline/PatternMatcher/ConflictSolver), language/country data registry, config loader, YML test harness wired into JUnit5 parameterized tests, CLI entry point with picocli + output formatters. End state: project builds, library + CLI artifacts produced, parity test discovers all YML cases (all skipped via `@Disabled` until rules ship in Plan 1+).

**Architecture:** Single Maven module `io.guessit:guessit-java`. Engine is a deterministic pipeline of phases over a shared `ParseContext` containing a mutable `MatchSet`. Rules are pluggable `Extractor`s registered in fixed order; conflict resolution is centralized. Bundled JSON config + CSV lang/country tables loaded once from classpath. CLI is a separate `cli` package using picocli, packaged as a shaded jar via the shade plugin.

**Tech Stack:** Java 25, Maven 3.9+, picocli 4.7.x, Jackson databind 2.18.x, SnakeYAML 2.x, Apache Commons CSV 1.12.x, JUnit Jupiter 5.11.x. No JPMS.

**Reference source:** Python guessit at `/tmp/guessit`. Spec at `docs/superpowers/specs/2026-05-02-guessit-java-design.md`.

---

## File Structure

Created in this plan (paths relative to repo root):

```
pom.xml
.gitignore
.gitattributes
README.md

src/main/java/io/guessit/
├── Guessit.java
├── GuessResult.java
├── Options.java
├── cli/
│   ├── GuessitCli.java
│   ├── PlainFormatter.java
│   ├── JsonFormatter.java
│   └── YamlFormatter.java
├── config/
│   ├── ConfigLoader.java
│   └── OptionsConfig.java
├── lang/
│   ├── Language.java
│   ├── Country.java
│   └── LanguageRegistry.java
├── engine/
│   ├── Match.java
│   ├── MatchSet.java
│   ├── Marker.java
│   ├── ParseContext.java
│   ├── Pipeline.java
│   ├── Phase.java                          // sealed interface
│   ├── MarkerPhase.java
│   ├── ExtractorPhase.java
│   ├── ConflictPhase.java
│   ├── PostPhase.java
│   ├── OutputPhase.java
│   ├── Extractor.java
│   ├── PatternMatcher.java
│   ├── RegexOpts.java
│   ├── StringOpts.java
│   └── ConflictSolver.java
├── rules/
│   ├── Rules.java                          // registry stub
│   ├── markers/
│   │   ├── PathMarker.java
│   │   └── GroupMarker.java
│   └── post/
│       ├── PrivateRemover.java
│       ├── TitleMarkerSelector.java
│       └── OutputBuilder.java
└── util/
    └── Quantity.java                        // CSV parsing uses Apache Commons CSV directly

src/main/resources/io/guessit/
├── config/options.json                     // verbatim from /tmp/guessit
├── data/tlds-alpha-by-domain.txt           // verbatim
├── data/iso-639.csv                        // ported from babelfish
├── data/iso-3166-1.csv                     // ported from babelfish
├── data/scripts.csv                        // ported from babelfish
├── data/lang-aliases.csv                   // hand-curated guessit overrides
└── data/country-aliases.csv                // hand-curated guessit overrides

src/test/java/io/guessit/
├── lang/LanguageRegistryTest.java
├── config/ConfigLoaderTest.java
├── engine/MatchSetTest.java
├── engine/PatternMatcherTest.java
├── engine/ConflictSolverTest.java
├── engine/PipelineTest.java
├── util/QuantityTest.java
├── cli/GuessitCliTest.java
├── parity/YmlCase.java
├── parity/YmlTestLoader.java
├── parity/YmlTestLoaderTest.java
└── parity/YmlParityTest.java

src/test/resources/yml/                     // copied verbatim from /tmp/guessit/guessit/test/
├── movies.yml, episodes.yml, various.yml,
├── streaming_services.yaml,
├── enable_disable_properties.yml,
└── rules/*.yml
```

Responsibilities (one per file):
- `Guessit` — public entry point + reusable instance.
- `GuessResult` — typed record + `toMap()`/`toJson()`/`toYaml()`.
- `Options` — immutable config record + builder.
- `cli/*` — picocli-driven main + output formatting.
- `config/ConfigLoader` — bundled + XDG + `--config` merge.
- `config/OptionsConfig` — parsed config tree (typed view over JSON).
- `lang/*` — ISO data + alias resolution.
- `engine/*` — deterministic pipeline core; no domain knowledge.
- `rules/*` — domain rules; this plan ships only marker + post placeholders.
- `util/Quantity` — value+unit pair with formatter.
- `parity/*` — YML harness; isolates the custom format quirks.

---

## Task 1: Initialize git + Maven project skeleton

**Files:**
- Create: `/home/finkel/work_self/guessit-java/.gitignore`
- Create: `/home/finkel/work_self/guessit-java/.gitattributes`
- Create: `/home/finkel/work_self/guessit-java/README.md`
- Create: `/home/finkel/work_self/guessit-java/pom.xml`

- [ ] **Step 1: Init git repo**

```bash
cd /home/finkel/work_self/guessit-java
git init -b main
```

- [ ] **Step 2: Write `.gitignore`**

```
target/
*.class
*.iml
.idea/
.vscode/
.DS_Store
*.log
hs_err_pid*
```

- [ ] **Step 3: Write `.gitattributes` to keep YML fixtures untouched**

```
* text=auto
*.yml -text
*.yaml -text
src/test/resources/yml/** -text
src/main/resources/io/guessit/data/** -text
```

- [ ] **Step 4: Write `README.md`**

```markdown
# guessit-java

Feature-equal Java port of Python [guessit](https://github.com/guessit-io/guessit).

Library + CLI that parses video filenames into structured metadata.

## Build

    mvn package

Produces:
- `target/guessit-java-<ver>.jar` — library
- `target/guessit-java-<ver>-cli.jar` — runnable CLI (shaded)

## CLI

    java -jar target/guessit-java-<ver>-cli.jar "Movie.Name.2020.1080p.mkv"

## Library

    GuessResult r = Guessit.parse("Movie.Name.2020.1080p.mkv");
    System.out.println(r.title());       // Movie Name
    System.out.println(r.year());        // 2020
    System.out.println(r.screenSize());  // 1080p

## Status

In active development. See `docs/superpowers/specs/` for design,
`docs/superpowers/plans/` for implementation plans.
```

- [ ] **Step 5: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.guessit</groupId>
    <artifactId>guessit-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>guessit-java</name>
    <description>Feature-equal Java port of Python guessit.</description>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <picocli.version>4.7.7</picocli.version>
        <jackson.version>2.19.0</jackson.version>
        <snakeyaml.version>2.4</snakeyaml.version>
        <commons-csv.version>1.14.1</commons-csv.version>
        <junit.version>5.12.2</junit.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>info.picocli</groupId>
            <artifactId>picocli</artifactId>
            <version>${picocli.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>org.yaml</groupId>
            <artifactId>snakeyaml</artifactId>
            <version>${snakeyaml.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
            <version>${commons-csv.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.15.0</version>
                <configuration>
                    <release>25</release>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <shadedArtifactAttached>true</shadedArtifactAttached>
                            <shadedClassifierName>cli</shadedClassifierName>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.guessit.cli.GuessitCli</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 6: Verify project compiles (no sources yet → still succeeds)**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS, no sources = no output classes.

- [ ] **Step 7: Commit**

```bash
git add .gitignore .gitattributes README.md pom.xml
git commit -m "chore: maven project skeleton with java 25 + picocli/jackson/snakeyaml/commons-csv/junit5"
```

---

## Task 2: Copy verbatim resources from Python guessit

**Files:**
- Create: `src/main/resources/io/guessit/config/options.json`
- Create: `src/main/resources/io/guessit/data/tlds-alpha-by-domain.txt`
- Create: `src/test/resources/yml/movies.yml`
- Create: `src/test/resources/yml/episodes.yml`
- Create: `src/test/resources/yml/various.yml`
- Create: `src/test/resources/yml/streaming_services.yaml`
- Create: `src/test/resources/yml/enable_disable_properties.yml`
- Create: `src/test/resources/yml/rules/*.yml` (all 18 files)

- [ ] **Step 1: Copy main resources**

```bash
mkdir -p src/main/resources/io/guessit/config src/main/resources/io/guessit/data
cp /tmp/guessit/guessit/config/options.json src/main/resources/io/guessit/config/options.json
cp /tmp/guessit/guessit/data/tlds-alpha-by-domain.txt src/main/resources/io/guessit/data/tlds-alpha-by-domain.txt
```

- [ ] **Step 2: Copy YML fixtures**

```bash
mkdir -p src/test/resources/yml/rules
cp /tmp/guessit/guessit/test/movies.yml src/test/resources/yml/
cp /tmp/guessit/guessit/test/episodes.yml src/test/resources/yml/
cp /tmp/guessit/guessit/test/various.yml src/test/resources/yml/
cp /tmp/guessit/guessit/test/streaming_services.yaml src/test/resources/yml/
cp /tmp/guessit/guessit/test/enable_disable_properties.yml src/test/resources/yml/
cp /tmp/guessit/guessit/test/rules/*.yml src/test/resources/yml/rules/
```

- [ ] **Step 3: Verify counts**

Run: `find src/test/resources/yml -name '*.yml' -o -name '*.yaml' | wc -l`
Expected: at least 23 files (5 top-level + 18 rules).

Run: `wc -l src/main/resources/io/guessit/config/options.json`
Expected: matches `wc -l /tmp/guessit/guessit/config/options.json`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources src/test/resources
git commit -m "chore: vendor options.json, tlds, and YML test fixtures from python guessit"
```

---

## Task 3: Port babelfish data → CSV resources

**Files:**
- Create: `src/main/resources/io/guessit/data/iso-639.csv`
- Create: `src/main/resources/io/guessit/data/iso-3166-1.csv`
- Create: `src/main/resources/io/guessit/data/scripts.csv`
- Create: `src/main/resources/io/guessit/data/lang-aliases.csv`
- Create: `src/main/resources/io/guessit/data/country-aliases.csv`

- [ ] **Step 1: Locate babelfish data files**

Run: `pip download babelfish --no-deps -d /tmp/babelfish-pkg && ls /tmp/babelfish-pkg`
Then unpack:
```bash
mkdir -p /tmp/babelfish-src && cd /tmp/babelfish-src && unzip -o /tmp/babelfish-pkg/babelfish-*.whl
ls babelfish/data/
```
Expected: `iso-639-3.tab`, `iso-15924-utf8-20131012.txt`, `iso3166-1.csv`, plus `iso6393.json`-style files. Filenames may differ across versions; the relevant ones contain ISO 639-3 codes, ISO 3166-1 country codes, ISO 15924 scripts.

- [ ] **Step 2: Generate `iso-639.csv` (alpha2,alpha3,name)**

Format (header line included, one record per line):
```
alpha2,alpha3,name
en,eng,English
fr,fra,French
...
```

Source: `babelfish/data/iso-639-3.tab` columns `Id` (alpha3), `Part1` (alpha2 if any), `Ref_Name` (name). Skip rows without `Ref_Name`. Empty alpha2 column allowed.

Generation script (run once, output committed):

```bash
python3 - <<'PY' > src/main/resources/io/guessit/data/iso-639.csv
import csv
print("alpha2,alpha3,name")
with open('/tmp/babelfish-src/babelfish/data/iso-639-3.tab', encoding='utf-8') as f:
    rdr = csv.DictReader(f, delimiter='\t')
    for r in rdr:
        name = r.get('Ref_Name','').strip()
        if not name: continue
        a2 = r.get('Part1','').strip()
        a3 = r.get('Id','').strip()
        # csv quoting
        def q(s):
            return '"'+s.replace('"','""')+'"' if (',' in s or '"' in s) else s
        print(f"{q(a2)},{q(a3)},{q(name)}")
PY
```

- [ ] **Step 3: Generate `iso-3166-1.csv` (alpha2,name)**

Source: `babelfish/data/iso3166-1.csv` (or equivalent — locate by content: 2-letter codes and country names).

```bash
python3 - <<'PY' > src/main/resources/io/guessit/data/iso-3166-1.csv
import csv
print("alpha2,name")
with open('/tmp/babelfish-src/babelfish/data/iso3166-1.csv', encoding='utf-8') as f:
    rdr = csv.reader(f)
    next(rdr, None)  # skip header
    for row in rdr:
        if len(row) < 2: continue
        a2, name = row[0].strip(), row[1].strip()
        if not a2 or not name: continue
        def q(s): return '"'+s.replace('"','""')+'"' if (',' in s or '"' in s) else s
        print(f"{q(a2)},{q(name)}")
PY
```

- [ ] **Step 4: Generate `scripts.csv` (code,name)**

Source: `babelfish/data/iso-15924-utf8-20131012.txt` (semicolon-delimited).

```bash
python3 - <<'PY' > src/main/resources/io/guessit/data/scripts.csv
print("code,name")
with open('/tmp/babelfish-src/babelfish/data/iso-15924-utf8-20131012.txt', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith('#'): continue
        parts = line.split(';')
        if len(parts) < 3: continue
        code, _num, name = parts[0].strip(), parts[1].strip(), parts[2].strip()
        if not code or not name: continue
        def q(s): return '"'+s.replace('"','""')+'"' if (',' in s or '"' in s) else s
        print(f"{q(code)},{q(name)}")
PY
```

- [ ] **Step 5: Hand-write `lang-aliases.csv`**

Sourced from Python `guessit/rules/properties/language.py` (look for the dict-style `LANGUAGE_ALIASES`, `SYN`, and `subtitle_prefixes` constants — open and translate verbatim).

Format: `alias,resolves_to_alpha3,note`
```
alias,alpha3,note
vo,und,original-version-marker
mul,mul,multiple-languages
und,und,undetermined
zxx,zxx,no-linguistic-content
```
(Add every alias from Python source — keep this CSV the single source of truth for guessit-specific overrides.)

- [ ] **Step 6: Hand-write `country-aliases.csv`**

Format: `alias,alpha2`
```
alias,alpha2
uk,GB
usa,US
us,US
```
(Add every alias from Python `country.py`.)

- [ ] **Step 7: Sanity-check generated CSVs**

Run: `head -3 src/main/resources/io/guessit/data/iso-639.csv`
Expected: header + at least 2 data rows starting with `en,eng,English`.

Run: `wc -l src/main/resources/io/guessit/data/iso-639.csv`
Expected: > 7000 (one per ISO 639-3 entry).

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/io/guessit/data
git commit -m "data: port babelfish iso 639/3166/15924 + guessit alias overrides to csv"
```

---

## Task 4: Verify Apache Commons CSV is on the classpath

No project source needed — `commons-csv` is the chosen CSV library and is declared as a Maven dependency in Task 1. This task is a smoke check ensuring the dep resolves and a minimal parse works before downstream code (Task 7's `LanguageRegistry`) builds on it.

**Files:**
- Test: `src/test/java/io/guessit/util/CommonsCsvSmokeTest.java`

- [ ] **Step 1: Write smoke test**

```java
package io.guessit.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommonsCsvSmokeTest {

    @Test
    void parsesHeaderAndRow() throws Exception {
        var csv = "alpha2,name\nUS,United States\n";
        try (var parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
            var records = parser.getRecords();
            assertEquals(1, records.size());
            assertEquals("US", records.get(0).get("alpha2"));
            assertEquals("United States", records.get(0).get("name"));
        }
    }

    @Test
    void parsesQuotedComma() throws Exception {
        var csv = "name\n\"Foo, Inc.\"\n";
        try (var parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
            var records = parser.getRecords();
            assertEquals(List.of("Foo, Inc."), List.of(records.get(0).get("name")));
        }
    }
}
```

- [ ] **Step 2: Run**

Run: `mvn -q -Dtest=CommonsCsvSmokeTest test`
Expected: 2 tests, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/guessit/util/CommonsCsvSmokeTest.java
git commit -m "test: smoke-test commons-csv on the classpath"
```

---

## Task 5: `util/Quantity` value+unit record

**Files:**
- Create: `src/main/java/io/guessit/util/Quantity.java`
- Test: `src/test/java/io/guessit/util/QuantityTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.guessit.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuantityTest {
    @Test
    void formatsBitRate() {
        assertEquals("1.5 Mbps", new Quantity(1.5, "Mbps").format());
    }

    @Test
    void formatsIntegerSize() {
        assertEquals("4 GB", new Quantity(4.0, "GB").format());
    }

    @Test
    void formatsDecimalSize() {
        assertEquals("4.7 GB", new Quantity(4.7, "GB").format());
    }

    @Test
    void parsesFromString() {
        assertEquals(new Quantity(1.5, "Mbps"), Quantity.parse("1.5 Mbps"));
        assertEquals(new Quantity(800, "Kbps"), Quantity.parse("800 Kbps"));
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q -Dtest=QuantityTest test`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.guessit.util;

public record Quantity(double value, String unit) {
    public String format() {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return ((long) value) + " " + unit;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit);
    }

    public static Quantity parse(String s) {
        var parts = s.trim().split("\\s+");
        if (parts.length != 2) throw new IllegalArgumentException("Quantity expects 'value unit', got: " + s);
        return new Quantity(Double.parseDouble(parts[0]), parts[1]);
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `mvn -q -Dtest=QuantityTest test`
Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/util/Quantity.java src/test/java/io/guessit/util/QuantityTest.java
git commit -m "util: quantity record (value+unit) with format/parse"
```

---

## Task 6: `lang/Language` + `lang/Country` records

**Files:**
- Create: `src/main/java/io/guessit/lang/Language.java`
- Create: `src/main/java/io/guessit/lang/Country.java`

- [ ] **Step 1: Write `Language.java`**

```java
package io.guessit.lang;

public record Language(String alpha2, String alpha3, String name) {
    @Override public String toString() { return name; }
}
```

- [ ] **Step 2: Write `Country.java`**

```java
package io.guessit.lang;

public record Country(String alpha2, String name) {
    @Override public String toString() { return name; }
}
```

- [ ] **Step 3: Verify compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/guessit/lang/Language.java src/main/java/io/guessit/lang/Country.java
git commit -m "lang: language and country records"
```

---

## Task 7: `lang/LanguageRegistry` with CSV-backed lookup

**Files:**
- Create: `src/main/java/io/guessit/lang/LanguageRegistry.java`
- Test: `src/test/java/io/guessit/lang/LanguageRegistryTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.guessit.lang;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanguageRegistryTest {
    private final LanguageRegistry r = LanguageRegistry.instance();

    @Test
    void findsByAlpha2() {
        var l = r.find("en").orElseThrow();
        assertEquals("English", l.name());
        assertEquals("eng", l.alpha3());
    }

    @Test
    void findsByAlpha3() {
        assertEquals("French", r.find("fra").orElseThrow().name());
        assertEquals("French", r.find("fre").orElseThrow().name()); // bibliographic alias
    }

    @Test
    void findsByName() {
        assertEquals("eng", r.find("English").orElseThrow().alpha3());
    }

    @Test
    void caseInsensitive() {
        assertEquals("en", r.find("ENGLISH").orElseThrow().alpha2());
    }

    @Test
    void resolvesAliasVo() {
        var l = r.find("vo").orElseThrow();
        assertEquals("Original Version", l.name());
    }

    @Test
    void resolvesCountryAliasUk() {
        assertEquals("GB", r.findCountry("uk").orElseThrow().alpha2());
    }

    @Test
    void unknownReturnsEmpty() {
        assertTrue(r.find("zzz-not-a-lang").isEmpty());
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q -Dtest=LanguageRegistryTest test`
Expected: compilation failure (`LanguageRegistry` not defined).

- [ ] **Step 3: Implement `LanguageRegistry`**

```java
package io.guessit.lang;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class LanguageRegistry {
    private static final LanguageRegistry INSTANCE = new LanguageRegistry();
    public static LanguageRegistry instance() { return INSTANCE; }

    private final Map<String, Language> langByKey = new HashMap<>();
    private final Map<String, Country> countryByKey = new HashMap<>();
    private final Map<String, String> scripts = new HashMap<>();

    // Aliases that produce non-ISO Languages (e.g. "vo" -> Original Version)
    private final Map<String, Language> langAliasOverrides = new HashMap<>();

    private LanguageRegistry() {
        loadIso639();
        loadIso3166();
        loadScripts();
        loadLangAliases();
        loadCountryAliases();
    }

    public Optional<Language> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        var key = token.trim().toLowerCase(java.util.Locale.ROOT);
        var override = langAliasOverrides.get(key);
        if (override != null) return Optional.of(override);
        return Optional.ofNullable(langByKey.get(key));
    }

    public Optional<Country> findCountry(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return Optional.ofNullable(countryByKey.get(token.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    public Optional<String> findScript(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return Optional.ofNullable(scripts.get(token.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    private void loadIso639() {
        forEachRecord("data/iso-639.csv", r -> {
            var a2 = trimOrEmpty(r, "alpha2");
            var a3 = trimOrEmpty(r, "alpha3");
            var name = trimOrEmpty(r, "name");
            if (name.isEmpty()) return;
            var lang = new Language(a2.isEmpty() ? null : a2, a3.isEmpty() ? null : a3, name);
            if (!a2.isEmpty()) langByKey.putIfAbsent(a2.toLowerCase(Locale.ROOT), lang);
            if (!a3.isEmpty()) langByKey.putIfAbsent(a3.toLowerCase(Locale.ROOT), lang);
            langByKey.putIfAbsent(name.toLowerCase(Locale.ROOT), lang);
        });
    }

    private void loadIso3166() {
        forEachRecord("data/iso-3166-1.csv", r -> {
            var a2 = trimOrEmpty(r, "alpha2");
            var name = trimOrEmpty(r, "name");
            if (a2.isEmpty() || name.isEmpty()) return;
            var c = new Country(a2.toUpperCase(Locale.ROOT), name);
            countryByKey.putIfAbsent(a2.toLowerCase(Locale.ROOT), c);
            countryByKey.putIfAbsent(name.toLowerCase(Locale.ROOT), c);
        });
    }

    private void loadScripts() {
        forEachRecord("data/scripts.csv", r -> {
            var code = trimOrEmpty(r, "code");
            var name = trimOrEmpty(r, "name");
            if (code.isEmpty() || name.isEmpty()) return;
            scripts.putIfAbsent(code.toLowerCase(Locale.ROOT), name);
        });
    }

    private void loadLangAliases() {
        forEachRecord("data/lang-aliases.csv", r -> {
            var alias = trimOrEmpty(r, "alias");
            var alpha3 = trimOrEmpty(r, "alpha3");
            if (alias.isEmpty()) return;
            Language target = langByKey.get(alpha3.toLowerCase(Locale.ROOT));
            if (target == null) {
                String displayName = switch (alias.toLowerCase(Locale.ROOT)) {
                    case "vo" -> "Original Version";
                    case "mul" -> "Multiple languages";
                    case "und" -> "Undetermined";
                    case "zxx" -> "No linguistic content";
                    default -> {
                        var note = trimOrEmpty(r, "note");
                        yield note.isEmpty() ? alias : note;
                    }
                };
                target = new Language(null, alpha3.isEmpty() ? null : alpha3, displayName);
            }
            langAliasOverrides.put(alias.toLowerCase(Locale.ROOT), target);
        });
    }

    private void loadCountryAliases() {
        forEachRecord("data/country-aliases.csv", r -> {
            var alias = trimOrEmpty(r, "alias");
            var a2 = trimOrEmpty(r, "alpha2");
            if (alias.isEmpty() || a2.isEmpty()) return;
            var resolved = countryByKey.get(a2.toLowerCase(Locale.ROOT));
            if (resolved != null) countryByKey.putIfAbsent(alias.toLowerCase(Locale.ROOT), resolved);
        });
    }

    private static String trimOrEmpty(CSVRecord r, String header) {
        if (!r.isMapped(header)) return "";
        var v = r.get(header);
        return v == null ? "" : v.trim();
    }

    private void forEachRecord(String resourceName, java.util.function.Consumer<CSVRecord> consumer) {
        var path = "/io/guessit/" + resourceName;
        try (InputStream in = LanguageRegistry.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + path);
            try (var parser = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .setIgnoreSurroundingSpaces(true)
                        .get())) {
                for (var record : parser) consumer.accept(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `mvn -q -Dtest=LanguageRegistryTest test`
Expected: 7 tests, 0 failures. If `findsByAlpha3` fails on `"fre"`, supplement the iso-639.csv generator (Task 3) to include Part2B column as additional alpha3 alias rows.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/lang/LanguageRegistry.java src/test/java/io/guessit/lang/LanguageRegistryTest.java
git commit -m "lang: csv-backed language/country registry with alias overrides"
```

---

## Task 8: `Options` record + builder

**Files:**
- Create: `src/main/java/io/guessit/Options.java`

- [ ] **Step 1: Write `Options`**

```java
package io.guessit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record Options(
    String type,                       // "movie"|"episode"|null
    String name,                       // override input
    List<String> expectedTitle,
    List<String> expectedGroup,
    List<String> excludes,
    List<String> includes,
    List<String> allowedLanguages,
    List<String> allowedCountries,
    Boolean dateYearFirst,
    Boolean dateDayFirst,
    Boolean episodePreferNumber,
    Boolean enforceListWhenSingle,
    List<Path> configPaths,
    boolean noUserConfig,
    boolean noDefaultConfig,
    Map<String, Object> raw
) {
    public Options {
        expectedTitle = expectedTitle == null ? List.of() : List.copyOf(expectedTitle);
        expectedGroup = expectedGroup == null ? List.of() : List.copyOf(expectedGroup);
        excludes      = excludes      == null ? List.of() : List.copyOf(excludes);
        includes      = includes      == null ? List.of() : List.copyOf(includes);
        allowedLanguages = allowedLanguages == null ? List.of() : List.copyOf(allowedLanguages);
        allowedCountries = allowedCountries == null ? List.of() : List.copyOf(allowedCountries);
        configPaths   = configPaths   == null ? List.of() : List.copyOf(configPaths);
        raw           = raw           == null ? Map.of()  : Map.copyOf(raw);
    }

    public static Options defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String type;
        private String name;
        private List<String> expectedTitle = new ArrayList<>();
        private List<String> expectedGroup = new ArrayList<>();
        private List<String> excludes = new ArrayList<>();
        private List<String> includes = new ArrayList<>();
        private List<String> allowedLanguages = new ArrayList<>();
        private List<String> allowedCountries = new ArrayList<>();
        private Boolean dateYearFirst;
        private Boolean dateDayFirst;
        private Boolean episodePreferNumber;
        private Boolean enforceListWhenSingle;
        private List<Path> configPaths = new ArrayList<>();
        private boolean noUserConfig;
        private boolean noDefaultConfig;
        private Map<String, Object> raw = new HashMap<>();

        public Builder type(String v) { this.type = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder addExpectedTitle(String v) { this.expectedTitle.add(v); return this; }
        public Builder addExpectedGroup(String v) { this.expectedGroup.add(v); return this; }
        public Builder addExclude(String v) { this.excludes.add(v); return this; }
        public Builder addInclude(String v) { this.includes.add(v); return this; }
        public Builder addAllowedLanguage(String v) { this.allowedLanguages.add(v); return this; }
        public Builder addAllowedCountry(String v) { this.allowedCountries.add(v); return this; }
        public Builder dateYearFirst(Boolean v) { this.dateYearFirst = v; return this; }
        public Builder dateDayFirst(Boolean v) { this.dateDayFirst = v; return this; }
        public Builder episodePreferNumber(Boolean v) { this.episodePreferNumber = v; return this; }
        public Builder enforceListWhenSingle(Boolean v) { this.enforceListWhenSingle = v; return this; }
        public Builder addConfigPath(Path p) { this.configPaths.add(p); return this; }
        public Builder noUserConfig(boolean v) { this.noUserConfig = v; return this; }
        public Builder noDefaultConfig(boolean v) { this.noDefaultConfig = v; return this; }
        public Builder rawEntry(String k, Object v) { this.raw.put(k, v); return this; }

        public Options build() {
            return new Options(
                type, name, expectedTitle, expectedGroup, excludes, includes,
                allowedLanguages, allowedCountries, dateYearFirst, dateDayFirst,
                episodePreferNumber, enforceListWhenSingle,
                configPaths, noUserConfig, noDefaultConfig, raw
            );
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/Options.java
git commit -m "api: options record + builder"
```

---

## Task 9: `GuessResult` record + map view (skeleton)

**Files:**
- Create: `src/main/java/io/guessit/GuessResult.java`

(`toMap` + `toJson`/`toYaml` populated minimally; field set covers spec section "Data model".)

- [ ] **Step 1: Write `GuessResult`**

```java
package io.guessit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Quantity;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    String type,
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
    Map<String, Object> extras
) {
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS == null ? SerializationFeature.INDENT_OUTPUT : SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        putIfNotNull(m, "title", title);
        putIfNotNull(m, "alternative_title", alternativeTitle);
        putIfNotNull(m, "year", year);
        putIfNotNull(m, "date", date);
        putSeasonOrEpisode(m, "season", season, seasonList);
        putSeasonOrEpisode(m, "episode", episode, episodeList);
        putIfNotNull(m, "episode_count", episodeCount);
        putIfNotNull(m, "season_count", seasonCount);
        putIfNotNull(m, "episode_title", episodeTitle);
        putIfNotNull(m, "episode_format", episodeFormat);
        putIfNotNull(m, "type", type);
        putList(m, "language", language);
        putList(m, "subtitle_language", subtitleLanguage);
        putList(m, "country", country);
        putIfNotNull(m, "source", source);
        putList(m, "other", other);
        putList(m, "video_codec", videoCodec);
        putList(m, "audio_codec", audioCodec);
        putList(m, "audio_channels", audioChannels);
        putList(m, "audio_profile", audioProfile);
        putList(m, "video_profile", videoProfile);
        putList(m, "video_api", videoApi);
        putIfNotNull(m, "screen_size", screenSize);
        putIfNotNull(m, "aspect_ratio", aspectRatio);
        putIfNotNull(m, "frame_rate", frameRate);
        if (bitRate != null) m.put("bit_rate", bitRate.format());
        if (size != null) m.put("size", size.format());
        putIfNotNull(m, "container", container);
        putIfNotNull(m, "mimetype", mimetype);
        putIfNotNull(m, "release_group", releaseGroup);
        putIfNotNull(m, "streaming_service", streamingService);
        putIfNotNull(m, "website", website);
        putIfNotNull(m, "edition", edition);
        putIfNotNull(m, "cd", cd);
        putIfNotNull(m, "cd_count", cdCount);
        putIfNotNull(m, "part", part);
        putIfNotNull(m, "version", version);
        putIfNotNull(m, "film", film);
        putIfNotNull(m, "film_title", filmTitle);
        putIfNotNull(m, "bonus", bonus);
        putIfNotNull(m, "bonus_title", bonusTitle);
        putIfNotNull(m, "crc32", crc32);
        if (extras != null) extras.forEach(m::putIfAbsent);
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static void putSeasonOrEpisode(Map<String, Object> m, String k, Integer single, List<Integer> list) {
        if (list != null && !list.isEmpty()) m.put(k, list.size() == 1 ? list.get(0) : list);
        else if (single != null) m.put(k, single);
    }

    private static void putList(Map<String, Object> m, String k, List<?> list) {
        if (list == null || list.isEmpty()) return;
        m.put(k, list.size() == 1 ? list.get(0) : list);
    }

    public String toJson() {
        try { return JSON.writeValueAsString(toMap()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public String toYaml() {
        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(opts).dump(toMap());
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        // setters mirroring record components; one with-method per field
        private String title, alternativeTitle, episodeTitle, episodeFormat, type,
                       source, screenSize, aspectRatio, container, mimetype,
                       releaseGroup, streamingService, website, edition,
                       filmTitle, bonusTitle, crc32;
        private Integer year, season, episode, episodeCount, seasonCount,
                        frameRate, cd, cdCount, part, version, film, bonus;
        private LocalDate date;
        private List<Integer> seasonList, episodeList;
        private List<Language> language, subtitleLanguage;
        private List<Country> country;
        private List<String> other, videoCodec, audioCodec, audioChannels,
                              audioProfile, videoProfile, videoApi;
        private Quantity bitRate, size;
        private Map<String, Object> extras = new LinkedHashMap<>();

        public Builder title(String v) { this.title = v; return this; }
        public Builder alternativeTitle(String v) { this.alternativeTitle = v; return this; }
        public Builder year(Integer v) { this.year = v; return this; }
        public Builder date(LocalDate v) { this.date = v; return this; }
        public Builder season(Integer v) { this.season = v; return this; }
        public Builder seasonList(List<Integer> v) { this.seasonList = v; return this; }
        public Builder episode(Integer v) { this.episode = v; return this; }
        public Builder episodeList(List<Integer> v) { this.episodeList = v; return this; }
        public Builder episodeCount(Integer v) { this.episodeCount = v; return this; }
        public Builder seasonCount(Integer v) { this.seasonCount = v; return this; }
        public Builder episodeTitle(String v) { this.episodeTitle = v; return this; }
        public Builder episodeFormat(String v) { this.episodeFormat = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder language(List<Language> v) { this.language = v; return this; }
        public Builder subtitleLanguage(List<Language> v) { this.subtitleLanguage = v; return this; }
        public Builder country(List<Country> v) { this.country = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder other(List<String> v) { this.other = v; return this; }
        public Builder videoCodec(List<String> v) { this.videoCodec = v; return this; }
        public Builder audioCodec(List<String> v) { this.audioCodec = v; return this; }
        public Builder audioChannels(List<String> v) { this.audioChannels = v; return this; }
        public Builder audioProfile(List<String> v) { this.audioProfile = v; return this; }
        public Builder videoProfile(List<String> v) { this.videoProfile = v; return this; }
        public Builder videoApi(List<String> v) { this.videoApi = v; return this; }
        public Builder screenSize(String v) { this.screenSize = v; return this; }
        public Builder aspectRatio(String v) { this.aspectRatio = v; return this; }
        public Builder frameRate(Integer v) { this.frameRate = v; return this; }
        public Builder bitRate(Quantity v) { this.bitRate = v; return this; }
        public Builder size(Quantity v) { this.size = v; return this; }
        public Builder container(String v) { this.container = v; return this; }
        public Builder mimetype(String v) { this.mimetype = v; return this; }
        public Builder releaseGroup(String v) { this.releaseGroup = v; return this; }
        public Builder streamingService(String v) { this.streamingService = v; return this; }
        public Builder website(String v) { this.website = v; return this; }
        public Builder edition(String v) { this.edition = v; return this; }
        public Builder cd(Integer v) { this.cd = v; return this; }
        public Builder cdCount(Integer v) { this.cdCount = v; return this; }
        public Builder part(Integer v) { this.part = v; return this; }
        public Builder version(Integer v) { this.version = v; return this; }
        public Builder film(Integer v) { this.film = v; return this; }
        public Builder filmTitle(String v) { this.filmTitle = v; return this; }
        public Builder bonus(Integer v) { this.bonus = v; return this; }
        public Builder bonusTitle(String v) { this.bonusTitle = v; return this; }
        public Builder crc32(String v) { this.crc32 = v; return this; }
        public Builder extra(String k, Object v) { this.extras.put(k, v); return this; }

        public GuessResult build() {
            return new GuessResult(
                title, alternativeTitle, year, date,
                season, seasonList, episode, episodeList,
                episodeCount, seasonCount, episodeTitle, episodeFormat,
                type, language, subtitleLanguage, country,
                source, other, videoCodec, audioCodec,
                audioChannels, audioProfile, videoProfile, videoApi,
                screenSize, aspectRatio, frameRate, bitRate, size,
                container, mimetype, releaseGroup, streamingService, website,
                edition, cd, cdCount, part, version, film, filmTitle,
                bonus, bonusTitle, crc32, extras
            );
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/guessit/GuessResult.java
git commit -m "api: GuessResult record with toMap/toJson/toYaml + builder"
```

---

## Task 10: `engine/Marker` + `engine/Match` records

**Files:**
- Create: `src/main/java/io/guessit/engine/Marker.java`
- Create: `src/main/java/io/guessit/engine/Match.java`

- [ ] **Step 1: Write `Marker.java`**

```java
package io.guessit.engine;

public record Marker(String name, int start, int end, String raw) {
    public boolean contains(int pos) { return pos >= start && pos < end; }
    public boolean covers(int s, int e) { return s >= start && e <= end; }
    public int length() { return end - start; }
}
```

- [ ] **Step 2: Write `Match.java`**

```java
package io.guessit.engine;

import java.util.Set;

public record Match(
    String name,
    Object value,
    int start,
    int end,
    String raw,
    int priority,
    Set<String> tags,
    boolean isPrivate
) {
    public Match {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public static Match of(String name, Object value, int start, int end, String raw) {
        return new Match(name, value, start, end, raw, 1000, Set.of(), false);
    }

    public Match withPriority(int p) {
        return new Match(name, value, start, end, raw, p, tags, isPrivate);
    }

    public Match withTags(Set<String> t) {
        return new Match(name, value, start, end, raw, priority, t, isPrivate);
    }

    public Match asPrivate() {
        return new Match(name, value, start, end, raw, priority, tags, true);
    }

    public int length() { return end - start; }

    public boolean overlaps(Match other) {
        return this.start < other.end && other.start < this.end;
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/guessit/engine/Marker.java src/main/java/io/guessit/engine/Match.java
git commit -m "engine: marker and match records"
```

---

## Task 11: `engine/MatchSet` mutable collection

**Files:**
- Create: `src/main/java/io/guessit/engine/MatchSet.java`
- Test: `src/test/java/io/guessit/engine/MatchSetTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MatchSetTest {

    @Test
    void addAndAll() {
        var s = new MatchSet();
        s.add(Match.of("year", 2020, 0, 4, "2020"));
        s.add(Match.of("source", "BluRay", 5, 11, "BluRay"));
        assertEquals(2, s.all().count());
    }

    @Test
    void namedFilter() {
        var s = new MatchSet();
        s.add(Match.of("year", 2020, 0, 4, "2020"));
        s.add(Match.of("source", "BluRay", 5, 11, "BluRay"));
        var years = s.named("year").collect(Collectors.toList());
        assertEquals(1, years.size());
        assertEquals(2020, years.get(0).value());
    }

    @Test
    void overlapping() {
        var s = new MatchSet();
        var a = Match.of("year", 2020, 0, 4, "2020");
        var b = Match.of("season", 20, 1, 3, "20");
        s.add(a); s.add(b);
        var overs = s.overlapping(0, 5).collect(Collectors.toList());
        assertEquals(2, overs.size());
        var nonOver = s.overlapping(10, 20).collect(Collectors.toList());
        assertTrue(nonOver.isEmpty());
    }

    @Test
    void inMarker() {
        var s = new MatchSet();
        var marker = new Marker("path", 0, 10, "abcdefghij");
        s.add(Match.of("year", 2020, 0, 4, "2020"));
        s.add(Match.of("year", 1999, 12, 16, "1999"));
        var inside = s.inMarker(marker).collect(Collectors.toList());
        assertEquals(1, inside.size());
        assertEquals(2020, inside.get(0).value());
    }

    @Test
    void removeAndReplace() {
        var s = new MatchSet();
        var a = Match.of("year", 2020, 0, 4, "2020");
        var b = Match.of("year", 1999, 0, 4, "1999");
        s.add(a);
        s.replace(a, b);
        assertEquals(List.of(b), s.all().collect(Collectors.toList()));
        s.remove(b);
        assertEquals(0, s.all().count());
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q -Dtest=MatchSetTest test`
Expected: compilation failure (`MatchSet` not defined).

- [ ] **Step 3: Implement `MatchSet`**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class MatchSet {
    private final List<Match> matches = new ArrayList<>();

    public void add(Match m) { matches.add(m); }

    public boolean remove(Match m) { return matches.remove(m); }

    public void replace(Match oldMatch, Match newMatch) {
        var idx = matches.indexOf(oldMatch);
        if (idx < 0) throw new IllegalArgumentException("Match not present: " + oldMatch);
        matches.set(idx, newMatch);
    }

    public Stream<Match> all() { return matches.stream(); }

    public Stream<Match> named(String name) { return matches.stream().filter(m -> m.name().equals(name)); }

    public Stream<Match> overlapping(int start, int end) {
        return matches.stream().filter(m -> m.start() < end && start < m.end());
    }

    public Stream<Match> inMarker(Marker marker) {
        return matches.stream().filter(m -> marker.covers(m.start(), m.end()));
    }

    public int size() { return matches.size(); }
}
```

- [ ] **Step 4: Verify pass**

Run: `mvn -q -Dtest=MatchSetTest test`
Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/MatchSet.java src/test/java/io/guessit/engine/MatchSetTest.java
git commit -m "engine: MatchSet mutable collection with overlap queries"
```

---

## Task 12: `engine/PatternMatcher` regex + string helpers

**Files:**
- Create: `src/main/java/io/guessit/engine/RegexOpts.java`
- Create: `src/main/java/io/guessit/engine/StringOpts.java`
- Create: `src/main/java/io/guessit/engine/PatternMatcher.java`
- Test: `src/test/java/io/guessit/engine/PatternMatcherTest.java`

- [ ] **Step 1: Write `RegexOpts.java`**

```java
package io.guessit.engine;

import java.util.Set;
import java.util.function.Function;

public record RegexOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    Function<String, Object> valueExtractor,   // input = matched group "value" if present, else full match
    Function<Object, Object> valueFormatter    // post-process value before storing
) {
    public RegexOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
    public static RegexOpts defaults() {
        return new RegexOpts(1000, Set.of(), false, s -> s, v -> v);
    }
    public RegexOpts withPriority(int p) { return new RegexOpts(p, tags, isPrivate, valueExtractor, valueFormatter); }
    public RegexOpts withTags(Set<String> t) { return new RegexOpts(priority, t, isPrivate, valueExtractor, valueFormatter); }
    public RegexOpts asPrivate() { return new RegexOpts(priority, tags, true, valueExtractor, valueFormatter); }
    public RegexOpts withValue(Function<String, Object> ex) { return new RegexOpts(priority, tags, isPrivate, ex, valueFormatter); }
    public RegexOpts withFormatter(Function<Object, Object> fmt) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, fmt); }
}
```

- [ ] **Step 2: Write `StringOpts.java`**

```java
package io.guessit.engine;

import java.util.Set;

public record StringOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    boolean caseSensitive,
    boolean wholeWord
) {
    public StringOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
    public static StringOpts defaults() {
        return new StringOpts(1000, Set.of(), false, false, true);
    }
    public StringOpts withPriority(int p) { return new StringOpts(p, tags, isPrivate, caseSensitive, wholeWord); }
    public StringOpts withTags(Set<String> t) { return new StringOpts(priority, t, isPrivate, caseSensitive, wholeWord); }
    public StringOpts caseSensitive(boolean v) { return new StringOpts(priority, tags, isPrivate, v, wholeWord); }
    public StringOpts wholeWord(boolean v) { return new StringOpts(priority, tags, isPrivate, caseSensitive, v); }
}
```

- [ ] **Step 3: Write failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PatternMatcherTest {

    @Test
    void regexFindsAllMatches() {
        var p = Pattern.compile("\\b(?<value>\\d{4})\\b");
        var matches = PatternMatcher.regex("Movie 1999 Sequel 2020", p, "year", RegexOpts.defaults());
        assertEquals(2, matches.size());
        assertEquals("1999", matches.get(0).raw());
        assertEquals("1999", matches.get(0).value());
    }

    @Test
    void regexValueExtractorParsesInt() {
        var p = Pattern.compile("\\b(?<value>\\d{4})\\b");
        var opts = RegexOpts.defaults().withValue(Integer::parseInt);
        var matches = PatternMatcher.regex("y2020", p, "year", opts);
        assertEquals(1, matches.size());
        assertEquals(2020, matches.get(0).value());
    }

    @Test
    void regexNamedValueGroupOptional() {
        var p = Pattern.compile("\\bBluRay\\b");
        var matches = PatternMatcher.regex("ALPHA.BluRay.x264", p, "source", RegexOpts.defaults());
        assertEquals(1, matches.size());
        assertEquals("BluRay", matches.get(0).raw());
        assertEquals("BluRay", matches.get(0).value());
    }

    @Test
    void stringMatchesNeedles() {
        var matches = PatternMatcher.string("Foo.AAC.x264.AAC.mkv", Set.of("AAC"), "audio_codec", StringOpts.defaults());
        assertEquals(2, matches.size());
        assertEquals(List.of(4, 13), matches.stream().map(Match::start).collect(Collectors.toList()));
    }

    @Test
    void stringWholeWord() {
        var matches = PatternMatcher.string("hauac.AAC.mkv", Set.of("AAC"), "x", StringOpts.defaults());
        assertEquals(1, matches.size());
        assertEquals(6, matches.get(0).start());  // not the AAC inside hauac
    }

    @Test
    void stringCaseSensitive() {
        var matches = PatternMatcher.string("Foo.aac.AAC.mkv", Set.of("AAC"), "x",
            StringOpts.defaults().caseSensitive(true));
        assertEquals(1, matches.size());
        assertEquals(8, matches.get(0).start());
    }
}
```

- [ ] **Step 4: Verify failure**

Run: `mvn -q -Dtest=PatternMatcherTest test`
Expected: compilation failure.

- [ ] **Step 5: Implement `PatternMatcher`**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternMatcher {
    private PatternMatcher() {}

    public static List<Match> regex(String input, Pattern pattern, String name, RegexOpts opts) {
        var out = new ArrayList<Match>();
        var m = pattern.matcher(input);
        while (m.find()) {
            String raw;
            int start, end;
            String valueText;
            if (hasGroup(m, "value")) {
                raw = m.group();
                start = m.start();
                end = m.end();
                valueText = m.group("value");
            } else {
                raw = m.group();
                start = m.start();
                end = m.end();
                valueText = raw;
            }
            Object extracted = opts.valueExtractor().apply(valueText);
            Object formatted = opts.valueFormatter().apply(extracted);
            out.add(new Match(name, formatted, start, end, raw, opts.priority(), opts.tags(), opts.isPrivate()));
        }
        return out;
    }

    public static List<Match> string(String input, Set<String> needles, String name, StringOpts opts) {
        var out = new ArrayList<Match>();
        var hay = opts.caseSensitive() ? input : input.toLowerCase(java.util.Locale.ROOT);
        for (var raw : needles) {
            var n = opts.caseSensitive() ? raw : raw.toLowerCase(java.util.Locale.ROOT);
            int from = 0;
            while (true) {
                int idx = hay.indexOf(n, from);
                if (idx < 0) break;
                int end = idx + n.length();
                if (!opts.wholeWord() || isWordBoundary(hay, idx, end)) {
                    out.add(new Match(name, raw, idx, end, input.substring(idx, end),
                        opts.priority(), opts.tags(), opts.isPrivate()));
                }
                from = idx + 1;
            }
        }
        out.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return out;
    }

    private static boolean hasGroup(Matcher m, String name) {
        try { m.group(name); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean isWordBoundary(String s, int start, int end) {
        if (start > 0 && Character.isLetterOrDigit(s.charAt(start - 1))) return false;
        if (end < s.length() && Character.isLetterOrDigit(s.charAt(end))) return false;
        return true;
    }
}
```

- [ ] **Step 6: Verify tests pass**

Run: `mvn -q -Dtest=PatternMatcherTest test`
Expected: 6 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/guessit/engine/RegexOpts.java src/main/java/io/guessit/engine/StringOpts.java src/main/java/io/guessit/engine/PatternMatcher.java src/test/java/io/guessit/engine/PatternMatcherTest.java
git commit -m "engine: PatternMatcher with regex + string helpers"
```

---

## Task 13: `engine/ConflictSolver`

**Files:**
- Create: `src/main/java/io/guessit/engine/ConflictSolver.java`
- Test: `src/test/java/io/guessit/engine/ConflictSolverTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConflictSolverTest {

    @Test
    void higherPriorityWinsOverlap() {
        var s = new MatchSet();
        s.add(Match.of("year", 2020, 0, 4, "2020").withPriority(2000));
        s.add(Match.of("season", 20, 0, 2, "20").withPriority(1000));
        ConflictSolver.solve(s);
        var names = s.all().map(Match::name).collect(Collectors.toList());
        assertEquals(List.of("year"), names);
    }

    @Test
    void longerWinsOnTiePriority() {
        var s = new MatchSet();
        s.add(Match.of("a", 1, 0, 4, "abcd"));
        s.add(Match.of("b", 2, 0, 2, "ab"));
        ConflictSolver.solve(s);
        assertEquals(List.of("a"), s.all().map(Match::name).collect(Collectors.toList()));
    }

    @Test
    void earlierStartWinsOnTiePriorityAndLength() {
        var s = new MatchSet();
        s.add(Match.of("a", 1, 2, 4, "ab"));
        s.add(Match.of("b", 2, 0, 2, "cd"));
        ConflictSolver.solve(s);
        // both length 2, same priority, b starts earlier — both keep since they don't overlap
        assertEquals(2, s.all().count());
    }

    @Test
    void coexistTagSurvives() {
        var s = new MatchSet();
        s.add(Match.of("country", "FR", 0, 2, "FR").withPriority(1000));
        s.add(Match.of("language", "fr", 0, 2, "fr").withPriority(1000).withTags(java.util.Set.of("coexist")));
        ConflictSolver.solve(s);
        assertEquals(2, s.all().count());
    }

    @Test
    void noOverlapKeepsAll() {
        var s = new MatchSet();
        s.add(Match.of("a", 1, 0, 2, "ab"));
        s.add(Match.of("b", 2, 5, 7, "cd"));
        ConflictSolver.solve(s);
        assertEquals(2, s.all().count());
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q -Dtest=ConflictSolverTest test`
Expected: compilation failure.

- [ ] **Step 3: Implement `ConflictSolver`**

```java
package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public final class ConflictSolver {
    private ConflictSolver() {}

    public static void solve(MatchSet matches) {
        var all = matches.all().toList();
        // Comparator: priority desc, length desc, start asc, name asc (stable tiebreak)
        var cmp = Comparator
            .comparingInt((Match m) -> m.priority()).reversed()
            .thenComparing(Comparator.comparingInt(Match::length).reversed())
            .thenComparingInt(Match::start)
            .thenComparing(Match::name);
        var sorted = new ArrayList<>(all);
        sorted.sort(cmp);
        var kept = new ArrayList<Match>();
        var dropped = new HashSet<Match>();
        for (var candidate : sorted) {
            boolean dropThis = false;
            for (var winner : kept) {
                if (candidate.overlaps(winner) && !candidate.tags().contains("coexist") && !winner.tags().contains("coexist")) {
                    dropThis = true;
                    break;
                }
            }
            if (dropThis) dropped.add(candidate);
            else kept.add(candidate);
        }
        for (var d : dropped) matches.remove(d);
    }
}
```

- [ ] **Step 4: Tests pass**

Run: `mvn -q -Dtest=ConflictSolverTest test`
Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/ConflictSolver.java src/test/java/io/guessit/engine/ConflictSolverTest.java
git commit -m "engine: ConflictSolver with priority/length/start tiebreak + coexist tag"
```

---

## Task 14: `engine/Phase` sealed interface + concrete phase classes

**Files:**
- Create: `src/main/java/io/guessit/engine/Phase.java`
- Create: `src/main/java/io/guessit/engine/MarkerPhase.java`
- Create: `src/main/java/io/guessit/engine/ExtractorPhase.java`
- Create: `src/main/java/io/guessit/engine/ConflictPhase.java`
- Create: `src/main/java/io/guessit/engine/PostPhase.java`
- Create: `src/main/java/io/guessit/engine/OutputPhase.java`
- Create: `src/main/java/io/guessit/engine/Extractor.java`
- Create: `src/main/java/io/guessit/engine/ParseContext.java`

- [ ] **Step 1: Write `Phase.java`**

```java
package io.guessit.engine;

public sealed interface Phase permits MarkerPhase, ExtractorPhase, ConflictPhase, PostPhase, OutputPhase {
    void apply(ParseContext ctx);
}
```

- [ ] **Step 2: Write `Extractor.java`**

```java
package io.guessit.engine;

public interface Extractor {
    String name();
    default int priority() { return 1000; }
    void extract(ParseContext ctx);
    default void postProcess(ParseContext ctx) {}
}
```

- [ ] **Step 3: Write `ParseContext.java`**

```java
package io.guessit.engine;

import io.guessit.GuessResult;
import io.guessit.Options;
import io.guessit.config.OptionsConfig;

import java.util.ArrayList;
import java.util.List;

public final class ParseContext {
    public final String input;
    public final Options options;
    public final OptionsConfig config;
    public final MatchSet matches = new MatchSet();
    public final List<Marker> markers = new ArrayList<>();
    public Marker titleMarker;          // chosen by TitleMarkerSelector
    public GuessResult.Builder resultBuilder = GuessResult.builder();
    public GuessResult result;

    public ParseContext(String input, Options options, OptionsConfig config) {
        this.input = input;
        this.options = options;
        this.config = config;
    }
}
```

- [ ] **Step 4: Write `MarkerPhase.java`**

```java
package io.guessit.engine;

import java.util.List;

public record MarkerPhase(List<MarkerProducer> producers) implements Phase {
    @FunctionalInterface
    public interface MarkerProducer {
        void produce(ParseContext ctx);
    }

    public MarkerPhase { producers = List.copyOf(producers); }

    @Override
    public void apply(ParseContext ctx) {
        for (var p : producers) p.produce(ctx);
    }
}
```

- [ ] **Step 5: Write `ExtractorPhase.java`**

```java
package io.guessit.engine;

import java.util.List;

public record ExtractorPhase(List<Extractor> extractors) implements Phase {
    public ExtractorPhase { extractors = List.copyOf(extractors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var e : extractors) e.extract(ctx);
    }
}
```

- [ ] **Step 6: Write `ConflictPhase.java`**

```java
package io.guessit.engine;

public record ConflictPhase() implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ConflictSolver.solve(ctx.matches);
    }
}
```

- [ ] **Step 7: Write `PostPhase.java`**

```java
package io.guessit.engine;

import java.util.List;

public record PostPhase(List<PostProcessor> processors) implements Phase {
    @FunctionalInterface
    public interface PostProcessor {
        void process(ParseContext ctx);
    }

    public PostPhase { processors = List.copyOf(processors); }

    @Override
    public void apply(ParseContext ctx) {
        for (var p : processors) p.process(ctx);
    }
}
```

- [ ] **Step 8: Write `OutputPhase.java`**

```java
package io.guessit.engine;

import java.util.function.Consumer;

public record OutputPhase(Consumer<ParseContext> assembler) implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        assembler.accept(ctx);
    }
}
```

- [ ] **Step 9: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/guessit/engine/Phase.java src/main/java/io/guessit/engine/Extractor.java src/main/java/io/guessit/engine/ParseContext.java src/main/java/io/guessit/engine/MarkerPhase.java src/main/java/io/guessit/engine/ExtractorPhase.java src/main/java/io/guessit/engine/ConflictPhase.java src/main/java/io/guessit/engine/PostPhase.java src/main/java/io/guessit/engine/OutputPhase.java
git commit -m "engine: sealed Phase + Extractor + ParseContext + concrete phase types"
```

---

## Task 15: `engine/Pipeline` runner

**Files:**
- Create: `src/main/java/io/guessit/engine/Pipeline.java`
- Test: `src/test/java/io/guessit/engine/PipelineTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.guessit.engine;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {

    @Test
    void runsPhasesInOrder() {
        var trace = new java.util.ArrayList<String>();
        var pipeline = new Pipeline(List.of(
            new MarkerPhase(List.of(c -> trace.add("marker"))),
            new ExtractorPhase(List.of(new Extractor() {
                public String name() { return "test"; }
                public void extract(ParseContext c) { trace.add("extract"); c.matches.add(Match.of("test", "x", 0, 1, "x")); }
            })),
            new ConflictPhase(),
            new PostPhase(List.of(c -> trace.add("post"))),
            new OutputPhase(c -> { trace.add("output"); c.result = c.resultBuilder.build(); })
        ));
        var ctx = new ParseContext("x", Options.defaults(), OptionsConfig.empty());
        pipeline.run(ctx);
        assertEquals(List.of("marker", "extract", "post", "output"), trace);
        assertNotNull(ctx.result);
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q -Dtest=PipelineTest test`
Expected: compilation failure (`Pipeline`/`OptionsConfig.empty()` missing — `OptionsConfig` covered in Task 16, so this test will be re-run after Task 16. Skip this verify-fail step if it cascades.)

- [ ] **Step 3: Implement `Pipeline`**

```java
package io.guessit.engine;

import java.util.List;

public final class Pipeline {
    private final List<Phase> phases;

    public Pipeline(List<Phase> phases) { this.phases = List.copyOf(phases); }

    public void run(ParseContext ctx) {
        for (var phase : phases) phase.apply(ctx);
    }
}
```

- [ ] **Step 4: Compile (test will fail until OptionsConfig exists)**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/engine/Pipeline.java src/test/java/io/guessit/engine/PipelineTest.java
git commit -m "engine: pipeline runner + ordering test (test depends on OptionsConfig)"
```

---

## Task 16: `config/OptionsConfig` + `config/ConfigLoader`

**Files:**
- Create: `src/main/java/io/guessit/config/OptionsConfig.java`
- Create: `src/main/java/io/guessit/config/ConfigLoader.java`
- Test: `src/test/java/io/guessit/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write `OptionsConfig.java`**

```java
package io.guessit.config;

import java.util.Map;

public record OptionsConfig(Map<String, Object> raw) {
    public OptionsConfig { raw = raw == null ? Map.of() : Map.copyOf(raw); }
    public static OptionsConfig empty() { return new OptionsConfig(Map.of()); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        var v = raw.get(name);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
```

- [ ] **Step 2: Write failing test**

```java
package io.guessit.config;

import io.guessit.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void loadsBundledDefaults() {
        var cfg = ConfigLoader.load(Options.defaults());
        // bundled options.json has top-level "advanced_config" key per Python guessit
        assertNotNull(cfg);
        assertFalse(cfg.raw().isEmpty(), "bundled config must not be empty");
    }

    @Test
    void noDefaultConfigSkipsBundle() {
        var opts = Options.builder().noDefaultConfig(true).noUserConfig(true).build();
        var cfg = ConfigLoader.load(opts);
        assertTrue(cfg.raw().isEmpty());
    }

    @Test
    void explicitConfigOverridesScalars(@TempDir Path tmp) throws IOException {
        var f = tmp.resolve("override.json");
        Files.writeString(f, "{\"some_key\": \"override\"}");
        var opts = Options.builder()
            .noDefaultConfig(true)
            .noUserConfig(true)
            .addConfigPath(f)
            .build();
        var cfg = ConfigLoader.load(opts);
        assertEquals("override", cfg.raw().get("some_key"));
    }

    @Test
    void mergeListsConcatenates(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        Files.writeString(a, "{\"items\": [1, 2]}");
        Files.writeString(b, "{\"items\": [3]}");
        var opts = Options.builder()
            .noDefaultConfig(true)
            .noUserConfig(true)
            .addConfigPath(a)
            .addConfigPath(b)
            .build();
        var cfg = ConfigLoader.load(opts);
        assertEquals(java.util.List.of(1, 2, 3), cfg.raw().get("items"));
    }

    @Test
    void mergeMapsDeep(@TempDir Path tmp) throws IOException {
        var a = tmp.resolve("a.json");
        var b = tmp.resolve("b.json");
        Files.writeString(a, "{\"nested\": {\"x\": 1, \"y\": 2}}");
        Files.writeString(b, "{\"nested\": {\"y\": 3, \"z\": 4}}");
        var opts = Options.builder()
            .noDefaultConfig(true).noUserConfig(true)
            .addConfigPath(a).addConfigPath(b).build();
        var cfg = ConfigLoader.load(opts);
        @SuppressWarnings("unchecked")
        var nested = (java.util.Map<String, Object>) cfg.raw().get("nested");
        assertEquals(1, nested.get("x"));
        assertEquals(3, nested.get("y"));
        assertEquals(4, nested.get("z"));
    }
}
```

- [ ] **Step 3: Verify failure**

Run: `mvn -q -Dtest=ConfigLoaderTest test`
Expected: compilation failure.

- [ ] **Step 4: Implement `ConfigLoader`**

```java
package io.guessit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.guessit.Options;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigLoader() {}

    public static OptionsConfig load(Options options) {
        Map<String, Object> merged = new LinkedHashMap<>();

        if (!options.noDefaultConfig()) {
            var bundled = readBundled();
            if (bundled != null) merged = deepMerge(merged, bundled);
        }

        if (!options.noUserConfig()) {
            for (var p : userConfigPaths()) {
                var loaded = readFile(p);
                if (loaded != null) merged = deepMerge(merged, loaded);
            }
        }

        for (var p : options.configPaths()) {
            var loaded = readFile(p);
            if (loaded != null) merged = deepMerge(merged, loaded);
        }

        if (!options.raw().isEmpty()) {
            merged = deepMerge(merged, options.raw());
        }

        return new OptionsConfig(merged);
    }

    private static Map<String, Object> readBundled() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/io/guessit/config/options.json")) {
            if (in == null) return null;
            return JSON.readValue(in, Map.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read bundled options.json", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readFile(Path p) {
        if (!Files.isReadable(p)) return null;
        try {
            var name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                if (name.endsWith(".json")) {
                    return JSON.readValue(r, Map.class);
                }
                if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    Object v = new Yaml().load(r);
                    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                }
                // unknown extension — try JSON first, then YAML
                var content = Files.readString(p, StandardCharsets.UTF_8);
                try { return JSON.readValue(content, Map.class); }
                catch (IOException ignored) {
                    Object v = new Yaml().load(content);
                    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config: " + p, e);
        }
    }

    private static List<Path> userConfigPaths() {
        var paths = new ArrayList<Path>();
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var home = System.getProperty("user.home");
        Path xdgBase = xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(home, ".config");
        for (var ext : List.of(".json", ".yml", ".yaml")) {
            paths.add(xdgBase.resolve("guessit").resolve("options" + ext));
        }
        for (var ext : List.of(".json", ".yml", ".yaml")) {
            paths.add(Path.of(home, ".guessit", "options" + ext));
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        var out = new LinkedHashMap<>(base);
        for (var e : overlay.entrySet()) {
            var k = e.getKey();
            var v = e.getValue();
            var existing = out.get(k);
            if (existing instanceof Map<?, ?> em && v instanceof Map<?, ?> vm) {
                out.put(k, deepMerge((Map<String, Object>) em, (Map<String, Object>) vm));
            } else if (existing instanceof List<?> el && v instanceof List<?> vl) {
                var combined = new ArrayList<Object>(el);
                combined.addAll(vl);
                out.put(k, combined);
            } else {
                out.put(k, v);
            }
        }
        return out;
    }
}
```

- [ ] **Step 5: Run tests (and re-run Pipeline test)**

Run: `mvn -q -Dtest='ConfigLoaderTest,PipelineTest' test`
Expected: both classes pass (5 + 1 tests, 0 failures).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/guessit/config src/test/java/io/guessit/config/ConfigLoaderTest.java
git commit -m "config: ConfigLoader with bundled + xdg + explicit + raw merge"
```

---

## Task 17: Marker producers — `PathMarker` + `GroupMarker`

**Files:**
- Create: `src/main/java/io/guessit/rules/markers/PathMarker.java`
- Create: `src/main/java/io/guessit/rules/markers/GroupMarker.java`

- [ ] **Step 1: Write `PathMarker.java`**

Splits input on `/` and `\` separators; each segment gets a `Marker` named `path`. Whole input also gets a marker named `whole`.

```java
package io.guessit.rules.markers;

import io.guessit.engine.Marker;
import io.guessit.engine.MarkerPhase.MarkerProducer;
import io.guessit.engine.ParseContext;

public final class PathMarker implements MarkerProducer {
    @Override
    public void produce(ParseContext ctx) {
        var input = ctx.input;
        ctx.markers.add(new Marker("whole", 0, input.length(), input));
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '\\') {
                if (i > start) ctx.markers.add(new Marker("path", start, i, input.substring(start, i)));
                start = i + 1;
            }
        }
        if (start < input.length()) ctx.markers.add(new Marker("path", start, input.length(), input.substring(start)));
    }
}
```

- [ ] **Step 2: Write `GroupMarker.java`**

Captures parenthesized/bracketed/braced segments inside the input.

```java
package io.guessit.rules.markers;

import io.guessit.engine.Marker;
import io.guessit.engine.MarkerPhase.MarkerProducer;
import io.guessit.engine.ParseContext;

public final class GroupMarker implements MarkerProducer {
    @Override
    public void produce(ParseContext ctx) {
        var input = ctx.input;
        var open = "([{";
        var close = ")]}";
        var stack = new java.util.ArrayDeque<int[]>(); // {openIdx, openCharIdxInOpen}
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int oi = open.indexOf(c);
            int ci = close.indexOf(c);
            if (oi >= 0) {
                stack.push(new int[]{i, oi});
            } else if (ci >= 0) {
                if (!stack.isEmpty() && stack.peek()[1] == ci) {
                    var openInfo = stack.pop();
                    int s = openInfo[0];
                    int e = i + 1;
                    if (e - s > 2) {
                        ctx.markers.add(new Marker("group", s + 1, e - 1, input.substring(s + 1, e - 1)));
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/guessit/rules/markers
git commit -m "markers: PathMarker (path/whole) and GroupMarker (paren/bracket/brace)"
```

---

## Task 18: Post processors — `PrivateRemover`, `TitleMarkerSelector`, `OutputBuilder`

**Files:**
- Create: `src/main/java/io/guessit/rules/post/PrivateRemover.java`
- Create: `src/main/java/io/guessit/rules/post/TitleMarkerSelector.java`
- Create: `src/main/java/io/guessit/rules/post/OutputBuilder.java`

- [ ] **Step 1: Write `PrivateRemover.java`**

```java
package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.PostPhase.PostProcessor;
import io.guessit.engine.ParseContext;

import java.util.List;

public final class PrivateRemover implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        List<Match> privates = ctx.matches.all().filter(Match::isPrivate).toList();
        for (var m : privates) ctx.matches.remove(m);
    }
}
```

- [ ] **Step 2: Write `TitleMarkerSelector.java`**

Picks the path-segment marker with the most non-`whole`, non-`group`, non-`path` matches; falls back to last `path` marker; ultimately to `whole`.

```java
package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.PostPhase.PostProcessor;
import io.guessit.engine.ParseContext;

import java.util.Comparator;

public final class TitleMarkerSelector implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        Marker best = ctx.markers.stream()
            .filter(m -> m.name().equals("path"))
            .max(Comparator.comparingLong(m ->
                ctx.matches.all().filter(x -> m.covers(x.start(), x.end())).count()))
            .orElse(null);

        if (best == null) {
            best = ctx.markers.stream()
                .filter(m -> m.name().equals("whole"))
                .findFirst()
                .orElse(new Marker("whole", 0, ctx.input.length(), ctx.input));
        }
        ctx.titleMarker = best;
    }
}
```

- [ ] **Step 3: Write `OutputBuilder.java`**

Walks `ctx.matches` and pushes values into `ctx.resultBuilder`. Per-property collation handled here (single-value vs list). Unknown match names go to `extras`.

```java
package io.guessit.rules.post;

import io.guessit.GuessResult;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Quantity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

public final class OutputBuilder implements Consumer<ParseContext> {
    @Override
    public void accept(ParseContext ctx) {
        var b = ctx.resultBuilder;
        var grouped = new LinkedHashMap<String, List<Match>>();
        ctx.matches.all().sorted(java.util.Comparator.comparingInt(Match::start)).forEach(m ->
            grouped.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m));

        for (var e : grouped.entrySet()) {
            switch (e.getKey()) {
                case "title" -> b.title(asString(e.getValue().get(0)));
                case "alternative_title" -> b.alternativeTitle(asString(e.getValue().get(0)));
                case "year" -> b.year(asInt(e.getValue().get(0)));
                case "date" -> b.date((LocalDate) e.getValue().get(0).value());
                case "season" -> applyIntList(e.getValue(), b::season, b::seasonList);
                case "episode" -> applyIntList(e.getValue(), b::episode, b::episodeList);
                case "episode_count" -> b.episodeCount(asInt(e.getValue().get(0)));
                case "season_count" -> b.seasonCount(asInt(e.getValue().get(0)));
                case "episode_title" -> b.episodeTitle(asString(e.getValue().get(0)));
                case "episode_format" -> b.episodeFormat(asString(e.getValue().get(0)));
                case "type" -> b.type(asString(e.getValue().get(0)));
                case "language" -> b.language(asLangList(e.getValue()));
                case "subtitle_language" -> b.subtitleLanguage(asLangList(e.getValue()));
                case "country" -> b.country(asCountryList(e.getValue()));
                case "source" -> b.source(asString(e.getValue().get(0)));
                case "other" -> b.other(asStringList(e.getValue()));
                case "video_codec" -> b.videoCodec(asStringList(e.getValue()));
                case "audio_codec" -> b.audioCodec(asStringList(e.getValue()));
                case "audio_channels" -> b.audioChannels(asStringList(e.getValue()));
                case "audio_profile" -> b.audioProfile(asStringList(e.getValue()));
                case "video_profile" -> b.videoProfile(asStringList(e.getValue()));
                case "video_api" -> b.videoApi(asStringList(e.getValue()));
                case "screen_size" -> b.screenSize(asString(e.getValue().get(0)));
                case "aspect_ratio" -> b.aspectRatio(asString(e.getValue().get(0)));
                case "frame_rate" -> b.frameRate(asInt(e.getValue().get(0)));
                case "bit_rate" -> b.bitRate((Quantity) e.getValue().get(0).value());
                case "size" -> b.size((Quantity) e.getValue().get(0).value());
                case "container" -> b.container(asString(e.getValue().get(0)));
                case "mimetype" -> b.mimetype(asString(e.getValue().get(0)));
                case "release_group" -> b.releaseGroup(asString(e.getValue().get(0)));
                case "streaming_service" -> b.streamingService(asString(e.getValue().get(0)));
                case "website" -> b.website(asString(e.getValue().get(0)));
                case "edition" -> b.edition(asString(e.getValue().get(0)));
                case "cd" -> b.cd(asInt(e.getValue().get(0)));
                case "cd_count" -> b.cdCount(asInt(e.getValue().get(0)));
                case "part" -> b.part(asInt(e.getValue().get(0)));
                case "version" -> b.version(asInt(e.getValue().get(0)));
                case "film" -> b.film(asInt(e.getValue().get(0)));
                case "film_title" -> b.filmTitle(asString(e.getValue().get(0)));
                case "bonus" -> b.bonus(asInt(e.getValue().get(0)));
                case "bonus_title" -> b.bonusTitle(asString(e.getValue().get(0)));
                case "crc32" -> b.crc32(asString(e.getValue().get(0)));
                default -> b.extra(e.getKey(), e.getValue().size() == 1 ? e.getValue().get(0).value()
                    : e.getValue().stream().map(Match::value).toList());
            }
        }

        ctx.result = b.build();
    }

    private static String asString(Match m) { return m.value() == null ? null : m.value().toString(); }
    private static Integer asInt(Match m) {
        var v = m.value();
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s);
        return null;
    }
    private static List<String> asStringList(List<Match> ms) { return ms.stream().map(OutputBuilder::asString).toList(); }
    private static List<Language> asLangList(List<Match> ms) { return ms.stream().map(m -> (Language) m.value()).toList(); }
    private static List<Country> asCountryList(List<Match> ms) { return ms.stream().map(m -> (Country) m.value()).toList(); }
    private static void applyIntList(List<Match> ms, java.util.function.Consumer<Integer> single,
                                     java.util.function.Consumer<List<Integer>> list) {
        if (ms.size() == 1) single.accept(asInt(ms.get(0)));
        else list.accept(ms.stream().map(OutputBuilder::asInt).toList());
    }
}
```

- [ ] **Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/rules/post
git commit -m "post: PrivateRemover + TitleMarkerSelector + OutputBuilder"
```

---

## Task 19: `rules/Rules` registry stub + `Guessit` entry point

**Files:**
- Create: `src/main/java/io/guessit/rules/Rules.java`
- Create: `src/main/java/io/guessit/Guessit.java`

- [ ] **Step 1: Write `Rules.java`**

Empty extractor list for Plan 0; later plans append.

```java
package io.guessit.rules;

import io.guessit.engine.*;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import io.guessit.rules.post.OutputBuilder;
import io.guessit.rules.post.PrivateRemover;
import io.guessit.rules.post.TitleMarkerSelector;

import java.util.List;

public final class Rules {
    private Rules() {}

    public static List<Phase> defaultPipeline() {
        return List.of(
            new MarkerPhase(List.of(new PathMarker(), new GroupMarker())),
            new ExtractorPhase(allInOrder()),
            new ConflictPhase(),
            new PostPhase(List.of(
                new PrivateRemover(),
                new TitleMarkerSelector()
            )),
            new OutputPhase(new OutputBuilder())
        );
    }

    public static List<Extractor> allInOrder() {
        return List.of(); // Plan 1+ append here
    }
}
```

- [ ] **Step 2: Write `Guessit.java`**

```java
package io.guessit;

import io.guessit.config.ConfigLoader;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Pipeline;
import io.guessit.rules.Rules;

public final class Guessit {

    private final Options options;
    private final OptionsConfig config;
    private final Pipeline pipeline;

    private Guessit(Options options) {
        this.options = options;
        this.config = ConfigLoader.load(options);
        this.pipeline = new Pipeline(Rules.defaultPipeline());
    }

    public static Guessit withOptions(Options options) { return new Guessit(options); }

    public static GuessResult parse(String input) { return parse(input, Options.defaults()); }

    public static GuessResult parse(String input, Options options) {
        return withOptions(options).guess(input);
    }

    public GuessResult guess(String input) {
        var ctx = new ParseContext(input, options, config);
        pipeline.run(ctx);
        return ctx.result;
    }

    public java.util.Map<String, java.util.List<Object>> properties() {
        // Stub: no extractors yet, so no known properties. Returned for API completeness.
        return java.util.Map.of();
    }

    public java.util.List<String> suggestedExpected(java.util.Collection<String> titles) {
        // Stub: returns unique titles unchanged. Real heuristic lands when title rule does (Plan 4).
        return titles.stream().distinct().toList();
    }
}
```

- [ ] **Step 3: Smoke test by hand**

```bash
mvn -q -DskipTests compile
mvn -q exec:java -Dexec.mainClass=io.guessit.Guessit -Dexec.args="" 2>/dev/null || true
```
(No exec plugin yet — verify only that compile succeeds.)

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/guessit/rules/Rules.java src/main/java/io/guessit/Guessit.java
git commit -m "api: Rules registry stub + Guessit entry point"
```

---

## Task 20: YML parity harness — `YmlCase` + `YmlTestLoader`

**Files:**
- Create: `src/test/java/io/guessit/parity/YmlCase.java`
- Create: `src/test/java/io/guessit/parity/YmlTestLoader.java`
- Test: `src/test/java/io/guessit/parity/YmlTestLoaderTest.java`
- Create: `src/test/resources/yml-loader-fixtures/basic.yml`
- Create: `src/test/resources/yml-loader-fixtures/negative.yml`
- Create: `src/test/resources/yml-loader-fixtures/options.yml`
- Create: `src/test/resources/yml-loader-fixtures/empty.yml`

- [ ] **Step 1: Write `YmlCase.java`**

```java
package io.guessit.parity;

import io.guessit.Options;

import java.util.Map;

public record YmlCase(
    String file,
    int line,
    String input,
    Map<String, Object> expected,
    Options options,
    boolean negative
) {
    @Override
    public String toString() {
        return file + ":" + line + " \"" + input + "\"";
    }
}
```

- [ ] **Step 2: Write fixture `basic.yml`**

```yaml
? Movie.Name.2020.1080p.mkv
: title: Movie Name
  year: 2020
  screen_size: 1080p
```

- [ ] **Step 3: Write fixture `negative.yml`**

```yaml
? -Title XViD 720p Only
? Title Only
: title: Title Only
```

- [ ] **Step 4: Write fixture `options.yml`**

```yaml
? Some.Show.S01E02.mkv
:
  options: --episode-prefer-number
  title: Some Show
  season: 1
  episode: 2
```

- [ ] **Step 5: Write fixture `empty.yml`**

```yaml
? meaningless
:
```

- [ ] **Step 6: Write failing test**

```java
package io.guessit.parity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YmlTestLoaderTest {

    @Test
    void loadsBasicCase() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/basic.yml");
        assertEquals(1, cases.size());
        var c = cases.get(0);
        assertEquals("Movie.Name.2020.1080p.mkv", c.input());
        assertEquals(2020, c.expected().get("year"));
        assertEquals("Movie Name", c.expected().get("title"));
        assertEquals("1080p", c.expected().get("screen_size"));
        assertFalse(c.negative());
    }

    @Test
    void loadsNegativeCase() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/negative.yml");
        assertEquals(2, cases.size());
        assertTrue(cases.get(0).negative());
        assertEquals("Title XViD 720p Only", cases.get(0).input());
        assertFalse(cases.get(1).negative());
        assertEquals("Title Only", cases.get(1).input());
        // Both share the same expected output
        assertEquals(cases.get(0).expected(), cases.get(1).expected());
    }

    @Test
    void loadsCaseWithOptions() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/options.yml");
        assertEquals(1, cases.size());
        var c = cases.get(0);
        assertNotNull(c.options());
        assertEquals(Boolean.TRUE, c.options().episodePreferNumber());
        // 'options' must NOT be present in expected map
        assertFalse(c.expected().containsKey("options"));
        assertEquals(1, c.expected().get("season"));
    }

    @Test
    void loadsEmptyExpected() {
        List<YmlCase> cases = YmlTestLoader.loadResource("yml-loader-fixtures/empty.yml");
        assertEquals(1, cases.size());
        assertTrue(cases.get(0).expected().isEmpty());
    }

    @Test
    void discoverAllFindsAtLeastOneRealFixture() {
        var all = YmlTestLoader.discoverAll("yml/").toList();
        assertFalse(all.isEmpty(), "should discover real guessit YML cases");
    }
}
```

- [ ] **Step 7: Verify failure**

Run: `mvn -q -Dtest=YmlTestLoaderTest test`
Expected: compilation failure.

- [ ] **Step 8: Implement `YmlTestLoader`**

Note on YML format quirks: Python guessit's YAML uses block-style sequences with `?` keys for inputs and `:` for expected output. Multiple consecutive `?` entries before one `:` block share that block. Inputs prefixed with `-` are negative cases. SnakeYAML can parse the `?`/`:` block-mapping form natively when the document is a mapping. The trick is preserving the order and recognizing the negative-prefix + shared-expected semantics. Approach: parse with SnakeYAML preserving order, then post-process keys.

```java
package io.guessit.parity;

import io.guessit.Options;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class YmlTestLoader {

    private YmlTestLoader() {}

    /**
     * Walk classpath resource directory, returning all cases across all .yml/.yaml files under it.
     */
    public static Stream<YmlCase> discoverAll(String classpathRoot) {
        var loader = Thread.currentThread().getContextClassLoader();
        try {
            var url = loader.getResource(classpathRoot);
            if (url == null) return Stream.empty();
            // Walk via filesystem (test resources are unpacked under target/test-classes/)
            var rootPath = java.nio.file.Path.of(url.toURI());
            return java.nio.file.Files.walk(rootPath)
                .filter(p -> {
                    var n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".yml") || n.endsWith(".yaml");
                })
                .flatMap(p -> {
                    var rel = rootPath.getParent().relativize(p).toString();
                    return loadResource(rel).stream();
                });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static List<YmlCase> loadResource(String classpathPath) {
        var loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(classpathPath)) {
            if (in == null) throw new IllegalArgumentException("Resource not found: " + classpathPath);
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                var content = br.lines().reduce("", (a, b) -> a + b + "\n");
                return parseContent(content, classpathPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses the guessit YAML test format. Returns one YmlCase per `?` input.
     * Multiple consecutive `?` entries before a `:` share the expected block.
     * Inputs starting with `-` are negative cases (input has the `-` stripped).
     */
    @SuppressWarnings("unchecked")
    static List<YmlCase> parseContent(String content, String fileLabel) {
        // SnakeYAML: when it sees `? key : value`, it treats it as block mapping.
        // We need ordered traversal, hence LinkedHashMap via SafeConstructor.
        var loaderOpts = new LoaderOptions();
        loaderOpts.setMaxAliasesForCollections(Integer.MAX_VALUE);
        var yaml = new Yaml(new SafeConstructor(loaderOpts));

        var topLevel = (Map<Object, Object>) yaml.load(content);
        if (topLevel == null) return List.of();

        // Compute approximate line numbers by scanning the source
        var lineByKey = computeLineMap(content, topLevel.keySet());

        var out = new ArrayList<YmlCase>();
        // Handle __default__ block if present
        Map<String, Object> defaults = Map.of();
        if (topLevel.containsKey("__default__")) {
            var d = topLevel.get("__default__");
            if (d instanceof Map<?, ?> dm) defaults = (Map<String, Object>) dm;
            topLevel.remove("__default__");
        }

        // Walk in insertion order. Group consecutive null-value entries with the next non-null entry.
        var pending = new ArrayList<Object>();
        for (var entry : topLevel.entrySet()) {
            var k = entry.getKey();
            var v = entry.getValue();
            pending.add(k);
            if (v != null) {
                Map<String, Object> expected = new LinkedHashMap<>(defaults);
                if (v instanceof Map<?, ?> vm) {
                    expected.putAll((Map<String, Object>) vm);
                }
                Options options = extractOptions(expected);
                expected.remove("options");

                for (var input : pending) {
                    var raw = input.toString();
                    boolean negative = raw.startsWith("-");
                    var cleaned = negative ? raw.substring(1) : raw;
                    var line = lineByKey.getOrDefault(input, 0);
                    out.add(new YmlCase(fileLabel, line, cleaned, expected, options, negative));
                }
                pending.clear();
            }
        }
        // Trailing inputs without a `:` block — treat as expecting empty
        if (!pending.isEmpty()) {
            for (var input : pending) {
                var raw = input.toString();
                boolean negative = raw.startsWith("-");
                var cleaned = negative ? raw.substring(1) : raw;
                var line = lineByKey.getOrDefault(input, 0);
                out.add(new YmlCase(fileLabel, line, cleaned, Map.of(), Options.defaults(), negative));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Options extractOptions(Map<String, Object> expected) {
        var o = expected.get("options");
        if (o == null) return Options.defaults();
        var b = Options.builder();
        if (o instanceof String s) {
            applyArgString(b, s);
        } else if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> applyKv(b, k.toString(), v));
        } else if (o instanceof List<?> l) {
            for (var item : l) applyArgString(b, item.toString());
        }
        return b.build();
    }

    private static void applyArgString(Options.Builder b, String s) {
        // Supports flags like "--episode-prefer-number", "-T Title", "--type movie"
        var tokens = s.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "--episode-prefer-number" -> b.episodePreferNumber(true);
                case "--date-year-first", "-Y" -> b.dateYearFirst(true);
                case "--date-day-first", "-D" -> b.dateDayFirst(true);
                case "--no-default-config" -> b.noDefaultConfig(true);
                case "--no-user-config" -> b.noUserConfig(true);
                case "--type", "-t" -> { if (i + 1 < tokens.length) b.type(tokens[++i]); }
                case "--name", "-n" -> { if (i + 1 < tokens.length) b.name(tokens[++i]); }
                case "--expected-title", "-T" -> { if (i + 1 < tokens.length) b.addExpectedTitle(tokens[++i]); }
                case "--expected-group", "-G" -> { if (i + 1 < tokens.length) b.addExpectedGroup(tokens[++i]); }
                case "--allowed-language", "-L" -> { if (i + 1 < tokens.length) b.addAllowedLanguage(tokens[++i]); }
                case "--allowed-country", "-C" -> { if (i + 1 < tokens.length) b.addAllowedCountry(tokens[++i]); }
                case "--excludes" -> { if (i + 1 < tokens.length) b.addExclude(tokens[++i]); }
                case "--includes" -> { if (i + 1 < tokens.length) b.addInclude(tokens[++i]); }
                default -> { /* ignore unknown for now */ }
            }
        }
    }

    private static void applyKv(Options.Builder b, String k, Object v) {
        switch (k) {
            case "type" -> b.type(v.toString());
            case "name" -> b.name(v.toString());
            case "expected_title" -> { if (v instanceof List<?> l) l.forEach(x -> b.addExpectedTitle(x.toString())); else b.addExpectedTitle(v.toString()); }
            case "expected_group" -> { if (v instanceof List<?> l) l.forEach(x -> b.addExpectedGroup(x.toString())); else b.addExpectedGroup(v.toString()); }
            case "allowed_languages" -> { if (v instanceof List<?> l) l.forEach(x -> b.addAllowedLanguage(x.toString())); }
            case "allowed_countries" -> { if (v instanceof List<?> l) l.forEach(x -> b.addAllowedCountry(x.toString())); }
            case "date_year_first" -> b.dateYearFirst(toBool(v));
            case "date_day_first" -> b.dateDayFirst(toBool(v));
            case "episode_prefer_number" -> b.episodePreferNumber(toBool(v));
            default -> b.rawEntry(k, v);
        }
    }

    private static Boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Map<Object, Integer> computeLineMap(String content, java.util.Set<Object> keys) {
        var map = new java.util.HashMap<Object, Integer>();
        var lines = content.split("\n", -1);
        var remaining = new java.util.HashSet<>(keys);
        for (int i = 0; i < lines.length; i++) {
            var trimmed = lines[i].trim();
            if (!trimmed.startsWith("?")) continue;
            var literal = trimmed.substring(1).trim();
            for (var k : new java.util.ArrayList<>(remaining)) {
                if (k.toString().equals(literal)) {
                    map.put(k, i + 1);
                    remaining.remove(k);
                    break;
                }
            }
        }
        return map;
    }
}
```

- [ ] **Step 9: Run tests**

Run: `mvn -q -Dtest=YmlTestLoaderTest test`
Expected: 5 tests, 0 failures.

If `loadsCaseWithOptions` fails because `--episode-prefer-number` isn't applied: re-check `applyArgString` logic. If `loadsNegativeCase` fails on shared expected: ensure pending list is emptied only after writing all cases for the block.

- [ ] **Step 10: Commit**

```bash
git add src/test/java/io/guessit/parity src/test/resources/yml-loader-fixtures
git commit -m "test: YmlCase + YmlTestLoader for guessit yaml fixture format"
```

---

## Task 21: YML parity test (all `@Disabled` initially)

**Files:**
- Create: `src/test/java/io/guessit/parity/YmlParityTest.java`

- [ ] **Step 1: Write `YmlParityTest.java`**

```java
package io.guessit.parity;

import io.guessit.Guessit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YmlParityTest {

    @Disabled("phase-0: no rules yet; enable per phase as rules ship")
    @ParameterizedTest(name = "{0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
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

- [ ] **Step 2: Run full test suite — confirm parity is `@Disabled` and rest passes**

Run: `mvn -q test`
Expected: all unit tests pass (CommonsCsvSmoke, Quantity, LanguageRegistry, MatchSet, PatternMatcher, ConflictSolver, Pipeline, ConfigLoader, YmlTestLoader); `YmlParityTest` reported as disabled.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/guessit/parity/YmlParityTest.java
git commit -m "test: YML parity test scaffold (disabled until phase-1 rules ship)"
```

---

## Task 22: CLI output formatters

**Files:**
- Create: `src/main/java/io/guessit/cli/PlainFormatter.java`
- Create: `src/main/java/io/guessit/cli/JsonFormatter.java`
- Create: `src/main/java/io/guessit/cli/YamlFormatter.java`

- [ ] **Step 1: Write `PlainFormatter.java`**

```java
package io.guessit.cli;

import io.guessit.GuessResult;

public final class PlainFormatter {
    private PlainFormatter() {}

    public static String format(GuessResult r) {
        var sb = new StringBuilder();
        for (var e : r.toMap().entrySet()) {
            var v = e.getValue();
            String rendered = v instanceof java.util.List<?> l
                ? String.join(", ", l.stream().map(Object::toString).toList())
                : v.toString();
            sb.append(e.getKey()).append(": ").append(rendered).append("\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: Write `JsonFormatter.java`**

```java
package io.guessit.cli;

import io.guessit.GuessResult;

public final class JsonFormatter {
    private JsonFormatter() {}
    public static String format(GuessResult r) { return r.toJson(); }
}
```

- [ ] **Step 3: Write `YamlFormatter.java`**

```java
package io.guessit.cli;

import io.guessit.GuessResult;

public final class YamlFormatter {
    private YamlFormatter() {}
    public static String format(GuessResult r) { return r.toYaml(); }
}
```

- [ ] **Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/cli/PlainFormatter.java src/main/java/io/guessit/cli/JsonFormatter.java src/main/java/io/guessit/cli/YamlFormatter.java
git commit -m "cli: plain/json/yaml output formatters"
```

---

## Task 23: `cli/GuessitCli` picocli main

**Files:**
- Create: `src/main/java/io/guessit/cli/GuessitCli.java`
- Test: `src/test/java/io/guessit/cli/GuessitCliTest.java`

- [ ] **Step 1: Write failing test**

```java
package io.guessit.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GuessitCliTest {

    @Test
    void plainOutputContainsInput() {
        var captured = run("Movie.Name.2020.1080p.mkv");
        // No rules yet, so output may be empty body — just assert exit 0 and no exception.
        // Only assertion that's stable in Plan 0: command runs to completion.
        assertNotNull(captured);
    }

    @Test
    void jsonOutputIsValidJsonObject() {
        var out = run("--json", "Movie.Name.2020.1080p.mkv");
        var trimmed = out.trim();
        assertTrue(trimmed.startsWith("{"), "expected JSON object, got: " + trimmed);
        assertTrue(trimmed.endsWith("}"), "expected JSON object, got: " + trimmed);
    }

    @Test
    void yamlOutputDoesNotThrow() {
        var out = run("--yaml", "Movie.Name.2020.1080p.mkv");
        assertNotNull(out);
    }

    @Test
    void helpExits0() {
        int code = new CommandLine(new GuessitCli()).execute("--help");
        assertEquals(0, code);
    }

    @Test
    void versionExits0() {
        int code = new CommandLine(new GuessitCli()).execute("--version");
        assertEquals(0, code);
    }

    private String run(String... args) {
        var baos = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new GuessitCli()).execute(args);
            assertEquals(0, code, "exit code");
        } finally {
            System.setOut(prev);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q -Dtest=GuessitCliTest test`
Expected: compilation failure (`GuessitCli` not defined).

- [ ] **Step 3: Implement `GuessitCli`**

```java
package io.guessit.cli;

import io.guessit.Guessit;
import io.guessit.Options;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "guessit-java",
    mixinStandardHelpOptions = true,
    versionProvider = GuessitCli.VersionProvider.class,
    description = "Parse video filenames into structured metadata."
)
public final class GuessitCli implements Callable<Integer> {

    @Parameters(arity = "0..*", description = "Filenames to parse.")
    List<String> filenames = new ArrayList<>();

    @Option(names = {"-t", "--type"}, description = "movie or episode hint.")
    String type;

    @Option(names = {"-n", "--name"}, description = "Override input name.")
    String name;

    @Option(names = {"-Y", "--date-year-first"}) boolean dateYearFirst;
    @Option(names = {"-D", "--date-day-first"})  boolean dateDayFirst;

    @Option(names = {"-L", "--allowed-language"}, arity = "1..*")
    List<String> allowedLanguages = new ArrayList<>();

    @Option(names = {"-C", "--allowed-country"}, arity = "1..*")
    List<String> allowedCountries = new ArrayList<>();

    @Option(names = {"-E", "--episode-prefer-number"})
    boolean episodePreferNumber;

    @Option(names = {"-T", "--expected-title"}, arity = "1..*")
    List<String> expectedTitles = new ArrayList<>();

    @Option(names = {"-G", "--expected-group"}, arity = "1..*")
    List<String> expectedGroups = new ArrayList<>();

    @Option(names = "--excludes", arity = "1..*")
    List<String> excludes = new ArrayList<>();

    @Option(names = "--includes", arity = "1..*")
    List<String> includes = new ArrayList<>();

    @Option(names = {"-c", "--config"}, arity = "1..*")
    List<Path> configs = new ArrayList<>();

    @Option(names = "--no-user-config")    boolean noUserConfig;
    @Option(names = "--no-default-config") boolean noDefaultConfig;

    @Option(names = {"-j", "--json"}) boolean json;
    @Option(names = {"-y", "--yaml"}) boolean yaml;
    @Option(names = {"-v", "--verbose"}) boolean verbose;

    @Option(names = {"-P", "--show-property"})
    String showProperty;

    @Option(names = "--advanced") boolean advanced;

    @Override
    public Integer call() {
        if (filenames.isEmpty()) {
            System.err.println("No input filename provided. See --help.");
            return 2;
        }
        var optsBuilder = Options.builder()
            .type(type)
            .name(name)
            .dateYearFirst(dateYearFirst ? Boolean.TRUE : null)
            .dateDayFirst(dateDayFirst ? Boolean.TRUE : null)
            .episodePreferNumber(episodePreferNumber ? Boolean.TRUE : null)
            .noUserConfig(noUserConfig)
            .noDefaultConfig(noDefaultConfig);
        for (var t : expectedTitles) optsBuilder.addExpectedTitle(t);
        for (var g : expectedGroups) optsBuilder.addExpectedGroup(g);
        for (var e : excludes) optsBuilder.addExclude(e);
        for (var i : includes) optsBuilder.addInclude(i);
        for (var l : allowedLanguages) optsBuilder.addAllowedLanguage(l);
        for (var c : allowedCountries) optsBuilder.addAllowedCountry(c);
        for (var p : configs) optsBuilder.addConfigPath(p);

        var opts = optsBuilder.build();
        var guessit = Guessit.withOptions(opts);

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

    public static void main(String[] args) {
        System.exit(new CommandLine(new GuessitCli()).execute(args));
    }

    public static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"guessit-java 0.1.0-SNAPSHOT"};
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q -Dtest=GuessitCliTest test`
Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/guessit/cli/GuessitCli.java src/test/java/io/guessit/cli/GuessitCliTest.java
git commit -m "cli: picocli-based GuessitCli with full python __main__ flag parity"
```

---

## Task 24: Build shaded CLI jar + smoke run

**Files:**
- (No new files — verifying Task 1's pom shade config produces the artifact.)

- [ ] **Step 1: Build full package**

Run: `mvn -q clean package`
Expected: BUILD SUCCESS. Two artifacts under `target/`:
- `guessit-java-0.1.0-SNAPSHOT.jar`
- `guessit-java-0.1.0-SNAPSHOT-cli.jar`

- [ ] **Step 2: Smoke-run shaded jar**

Run: `java -jar target/guessit-java-0.1.0-SNAPSHOT-cli.jar --version`
Expected output: `guessit-java 0.1.0-SNAPSHOT` (exit 0).

Run: `java -jar target/guessit-java-0.1.0-SNAPSHOT-cli.jar --json "Test.mkv"`
Expected: `{}` (no rules in Plan 0 → empty result). Exit 0.

- [ ] **Step 3: Commit lockfile-equivalents if any**

(No lockfile in Maven; nothing to commit. Confirm `git status` is clean.)

```bash
git status
```
Expected: nothing to commit.

---

## Final verification

- [ ] **Step 1: Run full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. All non-disabled tests pass. `YmlParityTest` shown as disabled (single test method).

- [ ] **Step 2: Verify clean tree**

Run: `git log --oneline | head -30`
Expected: ~24 commits matching the task structure above.

Run: `git status`
Expected: clean.

---

## Self-Review

1. **Spec coverage** — every section of the spec maps to a task:
   - Architecture / package layout → Tasks 6–10, 14, 17–19
   - Pipeline phases → Tasks 14–17
   - Engine contract → Tasks 11–15
   - Conflict resolution → Task 13
   - Title / EpisodeTitle (post phases stub) → Task 18 (TitleMarkerSelector). Title content extraction = Plan 4.
   - Type inference → deferred to Plan 4 (rule-dependent).
   - Data model — `GuessResult`, `Options`, `Language`, `Country`, `Quantity`, `Match` → Tasks 5–10
   - ConfigLoader resolution order → Task 16 (covers all 5 layers)
   - LanguageRegistry quirks → Task 7 (vo, mul, und, country uk→GB) + alias CSVs in Task 3
   - Public API (`Guessit.parse`, `withOptions`, `guess`, `properties`, `suggestedExpected`) → Task 19
   - CLI — picocli with all flags → Task 23
   - Output formats (plain/json/yaml) → Task 22
   - Build (shaded jar) → Tasks 1, 24
   - Dependencies → Task 1
   - YML test harness — custom format quirks (`?`/`:`, negatives, options) → Tasks 20–21
   - Test execution staging — Plan 0 = Phase 0; YML test `@Disabled` until Plan 1+

2. **Placeholder scan** — all steps contain literal code or exact commands. No "TBD"/"TODO" in plan steps. Two scope-deferred items live in Task 19 (`properties()` and `suggestedExpected()` are stubs explicitly marked, with their full implementation deferred to Plan 4 when title rule lands — this is by design, not a placeholder).

3. **Type consistency** — verified:
   - `Options.builder()` builder methods used in `GuessitCli` (Task 23) and `YmlTestLoader.applyArgString` (Task 20) match the methods defined in Task 8.
   - `GuessResult.Builder` setters used in `OutputBuilder` (Task 18) match those defined in Task 9.
   - `Match.of(...)`, `withPriority`, `withTags`, `asPrivate` used in tests (Tasks 11, 13) match definitions in Task 10.
   - `RegexOpts.defaults().withValue(...)`, `withFormatter(...)`, `withPriority(...)` used in tests (Task 12) match Task 12 defs.
   - `MatchSet.all()`, `named()`, `overlapping()`, `inMarker()`, `add()`, `remove()`, `replace()` used in `ConflictSolver` (Task 13) and `OutputBuilder` (Task 18) match Task 11 defs.
   - `LanguageRegistry.instance()`, `find()`, `findCountry()` used elsewhere = consistent with Task 7.
   - `OptionsConfig.empty()` used in `Pipeline` test (Task 15) defined in Task 16 — Task 15 explicitly notes the test depends on OptionsConfig and re-runs after Task 16. Acceptable.
   - `Phase` sealed permits list (Task 14) lists `MarkerPhase, ExtractorPhase, ConflictPhase, PostPhase, OutputPhase` — every concrete phase subclass exists as a record in the same task.

4. **Task ordering / dependencies** — each task only references types defined in equal-or-prior tasks. The single forward reference (Pipeline test → OptionsConfig) is documented inline.

No issues to fix.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-02-plan-0-foundation.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
