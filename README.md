# guessit-java

Feature-equal Java port of Python [guessit](https://github.com/guessit-io/guessit) **3.8.0**.

Library + CLI that parses video filenames into structured metadata.

## Parity vs guessit 3.8.0

- **YML fixture parity: 100% (2076 / 2076 cases pass)** against the upstream
  test corpus shipped with guessit 3.8.0 (`guessit/test/*.yml` +
  `guessit/test/rules/*.yml`).
- All 24 upstream fixture files ported and green: `movies.yml`, `episodes.yml`,
  `various.yml`, plus per-rule fixtures (`audio_codec`, `bonus`, `cd`,
  `common_words`, `country`, `date`, `edition`, `enable_disable_properties`,
  `episodes`, `film`, `language`, `other`, `part`, `processors`,
  `release_group`, `screen_size`, `size`, `source`, `streaming_services`,
  `title`, `video_codec`, `website`).
- All output properties supported: `title`, `alternative_title`, `year`,
  `date`, `season`, `episode`, `episode_count`, `season_count`,
  `episode_title`, `episode_format`, `type`, `language`,
  `subtitle_language`, `country`, `source`, `other`, `video_codec`,
  `audio_codec`, `audio_channels`, `audio_profile`, `video_profile`,
  `video_api`, `screen_size`, `aspect_ratio`, `frame_rate`, `bit_rate`,
  `audio_bit_rate`, `video_bit_rate`, `size`, `container`, `mimetype`,
  `release_group`, `streaming_service`, `website`, `edition`, `cd`,
  `cd_count`, `part`, `version`, `film`, `film_title`, `bonus`,
  `bonus_title`, `crc32`, `proper_count`, plus extras (`color_depth`,
  `week`, `absolute_episode`, `episode_details`, `disc`).
- CLI flag parity with Python `__main__.py` (incl. `-t/--type`,
  `-n/--name-only`, `-c/--config`, `--no-default-config`, expected_*,
  verbose, JSON/YAML output, etc.).
- Config-loading parity: bundled `options.json` defaults + XDG /
  `~/.guessit/` user config + `--config`.
- Language/country aliases sourced from babelfish data + guessit overrides
  (CSV-embedded).

### Non-goals

Not a wire-compatible drop-in for the Python module API — Java consumers get
an idiomatic Java API; only the **output property map** is equivalent.

## Build

    mvn package

Produces:
- `target/guessit-java-<ver>.jar` — library
- `target/guessit-java-<ver>-cli.jar` — runnable CLI (shaded)

## CLI

    java -jar target/guessit-java-<ver>-cli.jar "Movie.Name.2020.1080p.mkv"

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

## Library

    GuessResult r = Guessit.parse("Movie.Name.2020.1080p.mkv");
    System.out.println(r.title());       // Movie Name
    System.out.println(r.year());        // 2020
    System.out.println(r.screenSize());  // 1080p

## Status

100% YML parity with guessit 3.8.0 reached. See `docs/superpowers/specs/`
for design, `docs/superpowers/plans/` for implementation history.
