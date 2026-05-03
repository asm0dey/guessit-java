package io.guessit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HolesTest {
    @Test void computeReturnsGapsBetweenMatches() {
        var input = "Movie.Name.2020.1080p.x264";
        var matches = List.of(
            Match.of("year", 2020, 11, 15, "2020"),
            Match.of("screen_size", "1080p", 16, 21, "1080p"),
            Match.of("video_codec", "H.264", 22, 26, "x264"));
        var holes = Holes.compute(input, 0, input.length(), matches, m -> false, null, Formatters::cleanup);
        assertEquals(1, holes.size());
        assertEquals("Movie Name", holes.get(0).value());
    }
    @Test void ignoredMatchesAreTransparent() {
        var input = "Hello.world.bar";
        var matches = List.of(Match.of("language", "en", 6, 11, "world"));
        var holes = Holes.compute(input, 0, input.length(), matches,
            m -> m.name().equals("language"), null, Formatters::cleanup);
        assertEquals(1, holes.size());
        assertEquals("Hello world bar", holes.get(0).value());
    }
    @Test void cropAroundMarker() {
        var input = "abc[def]ghi";
        var hole = new Holes.Hole(0, 11, input, Formatters::cleanup);
        var cropped = hole.crop(List.of(new Marker("group", 3, 8, "[def]")));
        assertEquals(2, cropped.size());
        assertEquals("abc", cropped.get(0).value());
        assertEquals("ghi", cropped.get(1).value());
    }
    @Test void splitOnTitleSeps() {
        var input = "Foo-Bar/Baz";
        var hole = new Holes.Hole(0, 11, input, s -> s);
        var parts = hole.split(Seps.TITLE_CHARS);
        assertEquals(List.of("Foo", "Bar", "Baz"),
            parts.stream().map(Holes.Hole::raw).toList());
    }
    @Test void emptyHoleSkipped() {
        var input = "ab";
        var matches = List.of(Match.of("x", null, 0, 2, "ab"));
        var holes = Holes.compute(input, 0, input.length(), matches, m -> false, null, Formatters::cleanup);
        assertTrue(holes.isEmpty());
    }
}
