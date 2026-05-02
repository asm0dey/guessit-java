package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseGroupExtractorTest {
    @Test void dashSeparatedAtEnd() {
        var r = Guessit.parse("Series.S01E02.Pilot.DVDRip.x264-CS.mkv").toMap();
        assertEquals("CS", r.get("release_group"));
    }
    @Test void dashSeparatedAtBeginning() {
        var r = Guessit.parse("abc-the.title.name.1983.1080p.bluray.x264.mkv").toMap();
        assertEquals("abc", r.get("release_group"));
    }
    @Test void scene() {
        var r = Guessit.parse("Something.XViD-ReleaseGroup.mkv").toMap();
        assertEquals("ReleaseGroup", r.get("release_group"));
    }
    @Test void animeBracketedAtStart() {
        var r = Guessit.parse("[ReleaseGroup] Something.S01E01.mkv").toMap();
        assertEquals("ReleaseGroup", r.get("release_group"));
    }
    @Test void expectedGroupWins() {
        var opts = Options.builder().expectedGroup(List.of("MyGroup")).build();
        var r = Guessit.parse("Movie.MyGroup.x264.mkv", opts).toMap();
        assertEquals("MyGroup", r.get("release_group"));
    }
}
