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
