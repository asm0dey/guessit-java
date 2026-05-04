package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.Options;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Targeted regression checks for YML parity diffs that have been fixed.
 *  New diagnoses go here first to anchor a fix; once a parity case lands
 *  the assertion stays as a guard. */
class ProbeTest {

    @Test
    void dateSeries_titleKeepsSeries() {
        var r = Guessit.parse("Date.Series.10-11-2008.XViD").toMap();
        assertThat(r.get("title")).isEqualTo("Date Series");
        assertThat(r.get("date")).isNotNull();
    }

    @Test
    void epiTypeEpisode_titleEpi() {
        var r = Guessit.parse("epi", Options.builder().type("episode").build()).toMap();
        assertThat(r.get("title")).isEqualTo("epi");
    }

    @Test
    void flexgetSeries() {
        var r = Guessit.parse("FlexGet.Series.2013.14.of.21.Title.Here.720p.HDTV.AAC5.1.x264-NOGRP").toMap();
        assertThat(r.get("title")).isEqualTo("FlexGet Series");
    }

    @Test
    void fumetsuNoAnataE_keepsTrailingE() {
        var r = Guessit.parse("[Erai-raws] Fumetsu no Anata e - 03 [720p][Multiple Subtitle].mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Fumetsu no Anata e");
    }

    @Test
    void showE02v2_versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv").toMap();
        assertThat(r.get("episode")).isEqualTo(2);
        assertThat(r.get("version")).isEqualTo(2);
    }
}
