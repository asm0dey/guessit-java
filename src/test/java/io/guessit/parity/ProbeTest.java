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

    @Test
    void showName313To315_dotsAbsoluteEpisode() {
        var r = Guessit.parse("Show.Name.313-315.s16e03-05").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat((java.util.List<Integer>) r.get("absolute_episode")).containsExactlyInAnyOrder(313, 314, 315);
        assertThat(r.get("season")).isEqualTo(16);
    }

    @Test
    void showName313To315_spacesAbsoluteEpisode() {
        var r = Guessit.parse("Show Name - 313-315 - s16e03-05").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat((java.util.List<Integer>) r.get("absolute_episode")).containsExactlyInAnyOrder(313, 314, 315);
        assertThat(r.get("season")).isEqualTo(16);
    }

    @Test
    void insider_bonusTitleKeepsLeadingNumber() {
        var r = Guessit.parse("The_Insider-(1999)-x02-60_Minutes_Interview-1996.mp4").toMap();
        assertThat(r.get("title")).isEqualTo("The Insider");
        assertThat(r.get("year")).isEqualTo(1999);
        assertThat(r.get("bonus")).isEqualTo(2);
        assertThat(r.get("bonus_title")).isEqualTo("60 Minutes Interview-1996");
        assertThat(r.get("alternative_title")).isNull();
    }
}
