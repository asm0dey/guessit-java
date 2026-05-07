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
        // Output starts with input line indented by 2 spaces, then underline row.
        assertThat(out).startsWith("  XxX.2020.mkv\n");
        assertThat(out).contains("----");
        assertThat(out).contains("year");
        assertThat(out).contains("container");
    }

    @Test
    void stacksOverlappingLabelsOnSeparateRows() {
        var year      = match(MatchName.YEAR,        2024, 4, 8,  "2024");
        var screen    = match(MatchName.SCREEN_SIZE, "1080p", 9, 14, "1080p");
        var src       = match(MatchName.SOURCE,      "WEB-DL", 15, 21, "WEB-DL");
        String out = SpanRenderer.render("XxX.2024.1080p.WEB-DL", List.of(year, screen, src), List.of());
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
        // Only one underline run; only one label row containing "year".
        long yearLines = out.lines().filter(l -> l.contains("year")).count();
        assertThat(yearLines).isEqualTo(1L);
    }

    @Test
    void handlesEmptyInput() {
        String out = SpanRenderer.render("foo", List.of(), List.of());
        assertThat(out).isEqualTo("  foo\n");
    }

    private static Match match(MatchName name, Object value, int start, int end, String raw) {
        return Match.of(name, value, start, end, raw);
    }
}
