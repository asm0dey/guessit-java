package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseGroupExtractorTest {
    @Test void dashSeparatedAtEnd() {
        var r = Guessit.parse("Series.S01E02.Pilot.DVDRip.x264-CS.mkv");
        assertThat(r.releaseGroup()).isEqualTo("CS");
    }
    @Test void dashSeparatedAtBeginning() {
        var r = Guessit.parse("abc-the.title.name.1983.1080p.bluray.x264.mkv");
        assertThat(r.releaseGroup()).isEqualTo("abc");
    }
    @Test void scene() {
        var r = Guessit.parse("Something.XViD-ReleaseGroup.mkv");
        assertThat(r.releaseGroup()).isEqualTo("ReleaseGroup");
    }
    @Test void animeBracketedAtStart() {
        var r = Guessit.parse("[ReleaseGroup] Something.S01E01.mkv");
        assertThat(r.releaseGroup()).isEqualTo("ReleaseGroup");
    }
    @Test void expectedGroupWins() {
        var opts = OptionsBuilder.options().expectedGroup(List.of("MyGroup")).build();
        var r = Guessit.parse("Movie.MyGroup.x264.mkv", opts);
        assertThat(r.releaseGroup()).isEqualTo("MyGroup");
    }
}
