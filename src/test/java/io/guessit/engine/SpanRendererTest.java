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
        assertThat(out).startsWith("XxX.2020.mkv\n");
        assertThat(out).contains("─"); // ─
        assertThat(out).contains("year");
        assertThat(out).contains("container");
    }

    @Test
    void stacksOverlappingLabelsOnSeparateRows() {
        var year      = match(MatchName.YEAR,        2024, 4, 8,  "2024");
        var screen    = match(MatchName.SCREEN_SIZE, "1080p", 9, 14, "1080p");
        var src       = match(MatchName.SOURCE,      "WEB-DL", 15, 21, "WEB-DL");
        String out = SpanRenderer.render("XxX.2024.1080p.WEB-DL", List.of(year, screen, src), List.of());
        assertThat(out).startsWith("XxX.2024.1080p.WEB-DL\n");
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
        assertThat(out).contains("┌");
        assertThat(out).contains("┐");
    }

    @Test
    void skipsPrivateMatches() {
        var visible = new Match(MatchName.YEAR, 2020, 0, 4, "2020", 1000, java.util.Set.of(), false);
        var hidden  = new Match(MatchName.YEAR, 2020, 0, 4, "2020", 1000, java.util.Set.of(), true);
        String out = SpanRenderer.render("2020", List.of(visible, hidden), List.of());
        long yearLines = out.lines().filter(l -> l.contains("year")).count();
        assertThat(yearLines).isEqualTo(1L);
    }

    @Test
    void handlesEmptyInput() {
        String out = SpanRenderer.render("foo", List.of(), List.of());
        assertThat(out).isEqualTo("foo\n");
    }

    @Test
    void singleCharSpanRendersAsPipe() {
        String out = SpanRenderer.render("ab.X.cd",
            List.of(Match.of(MatchName.PART, 1, 3, 4, "X")), List.of());
        assertThat(out).contains("│"); // │
        assertThat(out).contains("part");
        // Box-drawing horizontal (─ U+2500) must not appear for single-char span
        assertThat(out).doesNotContain("─"); // ─
    }

    @Test
    void overlappingSameSpanMatchesEachGetOwnRow() {
        var year   = Match.of(MatchName.YEAR,   2024, 6, 10, "2024");
        var season = Match.of(MatchName.SEASON, 20,   6, 10, "2024");
        String out = SpanRenderer.render("Movie.2024.mkv", List.of(year, season), List.of());
        assertThat(out).contains("year");
        assertThat(out).contains("season");
        // Two separate underline rows, each containing ─
        long underlineRows = out.lines().filter(l -> l.contains("─")).count();
        assertThat(underlineRows).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void overlappingMarkersDoNotShareUnderline() {
        var whole = new Marker("whole", 0, 14, "Movie.2020.mkv");
        var path  = new Marker("path",  0, 14, "Movie.2020.mkv");
        String out = SpanRenderer.render("Movie.2020.mkv", List.of(), List.of(whole, path));
        // Two distinct underline rows, one per marker (markers use ┌─ ─┐ style)
        long markerRows = out.lines().filter(l -> l.contains("┌") || l.contains("┐")).count();
        assertThat(markerRows).isGreaterThanOrEqualTo(2L);
        assertThat(out).contains("whole");
        assertThat(out).contains("path");
    }

    @Test
    void rendersComplexLayoutVerbatim() {
        var title     = match(MatchName.TITLE,     "Show", 0,  4,  "Show");
        var season    = match(MatchName.SEASON,    1,      6,  8,  "01");
        var episode   = match(MatchName.EPISODE,   2,      9,  11, "02");
        var year      = match(MatchName.YEAR,      2024,   12, 16, "2024");
        var container = match(MatchName.CONTAINER, "mkv",  17, 20, "mkv");
        String out = SpanRenderer.render("Show.S01E02.2024.mkv",
                List.of(title, season, episode, year, container), List.of());
        String expected =
                """
                        Show.S01E02.2024.mkv
                        ──┬─  ─┬ ─┬ ──┬─ ─┬─
                        title  episode│   │
                            season  year  │
                                      container
                        """;
        assertThat(out).isEqualTo(expected);
    }

    @Test
    void rendersOverlappingMarkersVerbatim() {
        String input = "Shōgun (2024)/Season 1/Shōgun - S01E07 WEBDL-2160p.mkv";
        int yearStart      = input.indexOf("2024");
        int seasonNumStart = input.indexOf("Season 1") + 7;
        int seasonTokStart = input.indexOf("S01E07");
        int episodeTokStart = seasonTokStart + 3;
        int sourceStart    = input.indexOf("WEBDL");
        int screenStart    = input.indexOf("2160p");
        int containerStart = input.indexOf("mkv");
        int firstSlash     = input.indexOf('/');
        int secondSlash    = input.indexOf('/', firstSlash + 1);
        int parenOpen      = input.indexOf('(');
        int parenClose     = input.indexOf(')');

        var title     = match(MatchName.TITLE,       "Shōgun", 0,              6,                    "Shōgun");
        var year      = match(MatchName.YEAR,        2024,     yearStart,      yearStart + 4,        "2024");
        var season    = match(MatchName.SEASON,      1,        seasonTokStart + 1, seasonTokStart + 3, "01");
        var episode   = match(MatchName.EPISODE,     7,        episodeTokStart + 1, episodeTokStart + 3, "07");
        var source    = match(MatchName.SOURCE,      "WEBDL",  sourceStart,    sourceStart + 5,      "WEBDL");
        var screen    = match(MatchName.SCREEN_SIZE, "2160p",  screenStart,    screenStart + 5,      "2160p");
        var container = match(MatchName.CONTAINER,   "mkv",    containerStart, containerStart + 3,   "mkv");

        var whole     = new Marker("whole", 0, input.length(), input);
        var path1     = new Marker("path", 0, firstSlash,  input.substring(0, firstSlash));
        var path2     = new Marker("path", firstSlash + 1, secondSlash, input.substring(firstSlash + 1, secondSlash));
        var path3     = new Marker("path", secondSlash + 1, input.length(), input.substring(secondSlash + 1));
        var group     = new Marker("group", parenOpen, parenClose + 1, input.substring(parenOpen, parenClose + 1));

        String out = SpanRenderer.render(input,
                List.of(title, year, season, episode, source, screen, container),
                List.of(whole, path1, path2, path3, group));
        String expected =
                "Shōgun (2024)/Season 1/Shōgun - S01E07 WEBDL-2160p.mkv\n" +
                "───┬── ┌─  ─┐ ┌─    ─┐ ┌─                           ─┐\n" +
                " title  group   path                path\n" +
                "┌─         ─┐                    ─┬ ─┬ ──┬── ──┬── ─┬─\n" +
                "    path                       season│source   │container\n" +
                "                                  episode screen_size\n" +
                "┌─                                                  ─┐\n" +
                "                         whole\n" +
                "        ──┬─\n" +
                "        year\n";
        assertThat(out).isEqualTo(expected);
    }

    private static Match match(MatchName name, Object value, int start, int end, String raw) {
        return Match.of(name, value, start, end, raw);
    }
}
