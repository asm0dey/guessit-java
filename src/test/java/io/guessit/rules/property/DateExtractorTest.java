package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static java.time.LocalDate.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateExtractorTest {
    @Test void ymdDate() {
        var r = parse("Show.2002-04-22.HDTV.mkv");
        assertThat(r.date()).isEqualTo(of(2002, 4, 22));
    }
    @Test void dmyDate() {
        var r = parse("Show.17-06-1998.HDTV.mkv");
        assertThat(r.date()).isEqualTo(of(1998, 6, 17));
    }
    @Test void noDate() {
        var r = Guessit.parse("Show.S01E02.mkv");
        assertNull(r.date());
    }
    @Test void dateSuppressesEpisode() {
        var r = parse("Show.2002-04-22.S01E02.mkv");
        // date should be present; S01E02 uses clear SxxExx which is outside the date span,
        // so episode should survive. The suppression applies to bare numbers inside the date span.
        assertThat(r.date()).isEqualTo(of(2002, 4, 22));
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }
    @Test void dateSuppressesYear() {
        var r = parse("Show.2002-04-22.HDTV.mkv");
        assertThat(r.date()).isEqualTo(of(2002, 4, 22));
        assertNull(r.year());
    }
}
