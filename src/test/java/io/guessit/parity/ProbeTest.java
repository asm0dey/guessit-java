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
    void darkKnight_hqStaysOther_audioProfileAdjacencyRule() {
        var r = Guessit.parse(
            "The.Dark.Knight.IMAX.EDITION.HQ.BluRay.1080p.x264.AC3.Hindi.Eng.ETRG").toMap();
        assertThat(r.get("other")).isEqualTo("High Quality");
        assertThat(r.get("audio_profile")).isNull();
    }

    @Test
    void sxxAll_yieldsSeasonAndCompleteOther() {
        var r = Guessit.parse("Something.1xAll-FlexGet").toMap();
        assertThat(r.get("season")).isEqualTo(1);
        assertThat(r.get("other")).isEqualTo("Complete");
        assertThat(r.get("release_group")).isEqualTo("FlexGet");
    }

    @Test
    void rangeFiller_acrossSxxExxPair() {
        var r = Guessit.parse("My.Name.Is.Earl.S01E01-S01E21.SWE-SUB").toMap();
        @SuppressWarnings("unchecked")
        var ep = (java.util.List<Integer>) r.get("episode");
        assertThat(ep).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21);
    }

    @Test
    void s01e01e07_fooBarGroup_uuidNotSwallowsEpisodeTitle() {
        var r = Guessit.parse("Test.S01E01E07-FooBar-Group.avi").toMap();
        assertThat(r.get("episode_title")).isEqualTo("FooBar-Group");
        assertThat(r.get("uuid")).isNull();
        assertThat(r.get("episode")).isEqualTo(java.util.List.of(1, 7));
    }

    @Test
    void showE02v2_versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv").toMap();
        assertThat(r.get("episode")).isEqualTo(2);
        assertThat(r.get("version")).isEqualTo(2);
    }

    @Test
    void ymlLoaderParsesDashTOption() {
        var content = """
                ? Show.Name.-.476-479.(2007).[HorribleSubs][WEBRip]..[HD.720p]
                : options: -t episode
                  episode:
                  - 476
                  - 477
                  - 478
                  - 479
                  source: Web
                  other: [Rip, HD]
                  release_group: HorribleSubs
                  screen_size: 720p
                  title: Show Name
                  type: episode
                  year: 2007
                """;
        var cases = io.guessit.parity.YmlTestLoader.parseContent(content, "probe");
        assertThat(cases).hasSize(1);
        assertThat(cases.getFirst().options().type()).isEqualTo("episode");
    }

    @Test
    void showName476To479_episodeRange() {
        var r = Guessit.parse("Show.Name.-.476-479.(2007).[HorribleSubs][WEBRip]..[HD.720p]").toMap();
        assertThat((java.util.List<Integer>) r.get("episode")).containsExactlyInAnyOrder(476, 477, 478, 479);
        assertThat(r.get("title")).isEqualTo("Show Name");
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
