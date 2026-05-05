package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import io.guessit.lang.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.guessit.OptionsBuilder.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Targeted regression checks for YML parity diffs that have been fixed.
 *  New diagnoses go here first to anchor a fix; once a parity case lands
 *  the assertion stays as a guard. */
@SuppressWarnings("unchecked")
class ProbeTest {

    @Test
    void dateSeries_titleKeepsSeries() {
        var r = Guessit.parse("Date.Series.10-11-2008.XViD").toMap();
        assertThat(r.get("title")).isEqualTo("Date Series");
        assertThat(r.get("date")).isNotNull();
    }

    @Test
    void epiTypeEpisode_titleEpi() {
        var r = Guessit.parse("epi", options().type("episode").build()).toMap();
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
    void numXNumWithSpaces_seasonEpisodePair() {
        var r = Guessit.parse("Something 1 x 2-FlexGet",
            options().type("episode").build()).toMap();
        assertThat(r.get("season")).isEqualTo(1);
        assertThat(r.get("episode")).isEqualTo(2);
    }

    @Test
    void commeUneImage_outerFolderProperCase() {
        var r = Guessit.parse(
            "Movies/Comme une Image (2004)/Comme.Une.Image.FRENCH.DVDRiP.XViD-NTK.par-www.divx-overnet.com.avi").toMap();
        assertThat(r.get("title")).isEqualTo("Comme une Image");
    }

    @Test
    void duckman101Parens_keepsCompactSsee() {
        var r = Guessit.parse(
            "Series/Duckman/Duckman - 101 (01) - 20021107 - I, Duckman.avi").toMap();
        assertThat(r.get("season")).isEqualTo(1);
        assertThat(r.get("episode")).isEqualTo(1);
        assertThat(r.get("title")).isEqualTo("Duckman");
        assertThat(r.get("episode_title")).isEqualTo("I, Duckman");
    }

    @Test
    void preferTitleWithYear_tagsEquivalentIgnore() {
        // The kept title must carry "equivalent-ignore" so EquivalentHoles
        // doesn't replace its outer-folder value with the filename's
        // title-cased hole.
        var r = Guessit.parse("Foo Bar (2010)/foo.bar.2010.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Foo Bar");
    }

    @Test
    void subsAfterSubtitleLang_trailingDashRg() {
        var r1 = Guessit.parse("Show.Name.S01E02.HDTV.x264.NL-subs-ABC").toMap();
        assertThat(r1.get("subtitle_language")).isNotNull();
        assertThat(r1.get("release_group")).isEqualTo("ABC");

        var r2 = Guessit.parse(
            "FROZEN [2010] LiMiTED DVDRip H262 AAC[ ENG SUBS]-MANTESH").toMap();
        assertThat(r2.get("release_group")).isEqualTo("MANTESH");
    }

    @Test
    void subsDashRg_undSubtitleAndTrailingRg() {
        var r1 = Guessit.parse(
            "Life of Pi 2012 2160p 4K BluRay HDR10 HEVC BT2020 DTSHD 7.1 subs -DDR").toMap();
        assertThat(r1.get("subtitle_language")).isNotNull();
        assertThat(r1.get("release_group")).isEqualTo("DDR");

        var r2 = Guessit.parse("Family.Katta.2016.1080p.WEB-DL.H263.DD5.1.ESub-DDR").toMap();
        assertThat(r2.get("subtitle_language")).isNotNull();
        assertThat(r2.get("release_group")).isEqualTo("DDR");
    }

    @Test
    void mbcVodEmbedded_streamingService() {
        var r = Guessit.parse(
            "Eyes.Of.Dawn.1991.E01.480p.MBCVOD.AAC.x264-NOGPR.mp4").toMap();
        assertThat(r.get("streaming_service")).isEqualTo("MBC");
    }

    @Test
    void chuckBerry320Kbps_typeMovie_noEpisode() {
        var r = Guessit.parse("Chuck Berry The Very Best Of Chuck Berry(2010)[320 Kbps]").toMap();
        assertThat(r.get("type")).isEqualTo("movie");
        assertThat(r.get("year")).isEqualTo(2010);
        assertThat(r.get("audio_bit_rate")).isNotNull();
        assertThat(r.get("season")).isNull();
        assertThat(r.get("episode")).isNull();
    }

    @Test
    void epWordBeforeSxxExx_excludedFromTitle() {
        var r = Guessit.parse("Star Trek DS9 Ep 2x03 The Siege (Part III)").toMap();
        assertThat(r.get("title")).isEqualTo("Star Trek DS9");
        assertThat(r.get("season")).isEqualTo(2);
        assertThat(r.get("episode")).isEqualTo(3);
    }

    @Test
    void s01e01e07_fooBarGroup_uuidNotSwallowsEpisodeTitle() {
        var r = Guessit.parse("Test.S01E01E07-FooBar-Group.avi").toMap();
        assertThat(r.get("episode_title")).isEqualTo("FooBar-Group");
        assertThat(r.get("uuid")).isNull();
        assertThat(r.get("episode")).isEqualTo(List.of(1, 7));
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
    void enInsideEpisodeTitle_notLanguage() {
        var r = Guessit.parse(
            "Fear the Walking Dead - 01x02 - En Close, Yet En Far.REPACK-KILLERS.French.C.updated.Addic7ed.com.mkv").toMap();
        assertThat(r.get("episode_title")).isEqualTo("En Close, Yet En Far");
        var lang = r.get("language");
        if (lang instanceof java.util.List<?> l) {
            assertThat(l).extracting(o -> ((Language) o).alpha2()).doesNotContain("en");
        }
    }

    @Test
    void s01Extras_seasonAndOtherExtras() {
        var r = Guessit.parse("Series/My Name Is Earl/My.Name.Is.Earl.S01Extras.-.Bad.Karma.DVDRip.XviD.avi").toMap();
        assertThat(r.get("title")).isEqualTo("My Name Is Earl");
        assertThat(r.get("season")).isEqualTo(1);
        assertThat(r.get("episode_title")).isEqualTo("Bad Karma");
        assertThat((java.util.List<String>) r.get("other")).contains("Extras");
    }

    @Test
    void xctLePrestige_chapsReleaseGroup() {
        var r = Guessit.parse(
            "[XCT].Le.Prestige.(The.Prestige).DVDRip.[x264.HP.He-Aac.{Fr-Eng}.St{Fr-Eng}.Chaps].mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Le Prestige");
        assertThat(r.get("release_group")).isEqualTo("Chaps");
    }

    @Test
    void showName2_10_2016_animeContextKeepsWeakEpisode() {
        var r = Guessit.parse("Show!.Name.2.-.10.(2016).[HorribleSubs][WEBRip]..[HD.720p]").toMap();
        assertThat(r.get("episode")).isEqualTo(10);
        assertThat(r.get("year")).isEqualTo(2016);
        assertThat(r.get("type")).isEqualTo("episode");
        assertThat(r.get("release_group")).isEqualTo("HorribleSubs");
    }

    @Test
    void dimensionWTvDub_undLanguageAndTvSource() {
        var r = Guessit.parse("[MK-Pn8].Dimension.W.-.05.[720p][Hi10][Dual][TV-Dub][EDA6E7F1]",
            OptionsBuilder.options()
                .allowedLanguages(List.of("und"))
                .allowedCountries(List.of("us"))
                .build()).toMap();
        assertThat(r.get("source")).isEqualTo("TV");
        assertThat(r.get("language")).isNotNull();
    }

    @Test
    void splitScenesBetweenSourceAndContainer_notAltTitle() {
        var r = Guessit.parse(
            "French Maid Services - Lola At Your Service WEB-DL SPLIT SCENES MP4-RARBG").toMap();
        assertThat(r.get("title")).isEqualTo("French Maid Services");
        assertThat(r.get("alternative_title")).isEqualTo("Lola At Your Service");
        assertThat(r.get("release_group")).isEqualTo("RARBG");
    }

    @Test
    void subInGroup_promotesAllConsecutiveLanguagesAfter() {
        var r = Guessit.parse(
            "Show Name S01e10[Mux - 1080p - H264 - Ita Eng Ac3 - Sub Ita Eng]DLMux By GiuseppeTnT Littlelinx").toMap();
        @SuppressWarnings("unchecked")
        var subs = (java.util.List<Language>) r.get("subtitle_language");
        assertThat(subs).extracting(Language::alpha3).containsExactlyInAnyOrder("ita", "eng");
    }

    @Test
    void barFoodChristmasSpecial_episodeTypeViaSpecialDetail() {
        var r = Guessit.parse("BarFood christmas special HDTV",
            options().expectedTitle(List.of("BarFood")).build()).toMap();
        assertThat(r.get("title")).isEqualTo("BarFood");
        assertThat(r.get("episode_title")).isEqualTo("christmas special");
        assertThat(r.get("type")).isEqualTo("episode");
        assertThat(r.get("episode_details")).isEqualTo("Special");
    }

    @Test
    void queenAKindOfMagic_2cd_noCdAltTitle() {
        var r = Guessit.parse("Queen - A Kind of Magic (Alternative Extended Version) 2CD 2014").toMap();
        assertThat(r.get("title")).isEqualTo("Queen");
        assertThat(r.get("alternative_title")).isEqualTo("A Kind of Magic");
        assertThat(r.get("cd_count")).isEqualTo(2);
    }

    @Test
    void germanCustomSubbed_subtitleLanguage() {
        var r = Guessit.parse(
            "Show.Name.S04E21.Aint.Nothing.Like.the.Real.Thing.German.Custom.Subbed.720p.HDTV.x264.iNTERNAL-BaCKToRG").toMap();
        assertThat(r.get("subtitle_language")).isNotNull();
        assertThat(r.get("language")).isNull();
        assertThat(r.get("episode_title")).isEqualTo("Aint Nothing Like the Real Thing");
    }

    @Test
    void huaMulan_hrAsReleaseGroup() {
        var r = Guessit.parse("Hua.Mulan.BRRIP.MP4.x264.720p-HR.avi").toMap();
        assertThat(r.get("title")).isEqualTo("Hua Mulan");
        assertThat(r.get("release_group")).isEqualTo("HR");
        @SuppressWarnings("unchecked")
        var other = (java.util.List<String>) r.get("other");
        assertThat(other).doesNotContain("High Resolution");
    }

    @Test
    void waveyObfuscated_titleNotReleaseGroup() {
        var r = Guessit.parse("Season 06/e01.1080p.bluray.x264-wavey-obfuscated.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("wavey");
        assertThat(r.get("release_group")).isNull();
        assertThat(r.get("other")).isEqualTo("Obfuscated");
    }

    @Test
    void epsilonXpost_keepsReleaseGroupWhenLeadingTitleExists() {
        var r = Guessit.parse(
            "24.S01E02.1080p.BluRay.REMUX.AVC.DD.2.0-EPSiLON-xpost/eb518eaf33f641a1a8c6e0973a67aec2.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("24");
        assertThat(r.get("release_group")).isEqualTo("EPSiLON");
    }

    @Test
    void engineeringCatastrophes_wholeFilepartGroupKeepsTitle() {
        var r = Guessit.parse("[ Engineering Catastrophes S02E10 1080p AMZN WEB-DL DD+ 2.0 x264-TrollHD ]").toMap();
        assertThat(r.get("title")).isEqualTo("Engineering Catastrophes");
        assertThat(r.get("season")).isEqualTo(2);
        assertThat(r.get("episode")).isEqualTo(10);
        assertThat(r.get("release_group")).isEqualTo("TrollHD");
    }

    @Test
    void daiseiFreeIwatobiCrcSwallowsSxxExx() {
        var r = Guessit.parse("[Daisei] Free!：Iwatobi Swim Club - 01 ~ (BD 720p 10-bit AAC) [99E8E009].mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Free!：Iwatobi Swim Club");
        assertThat(r.get("episode")).isEqualTo(1);
        assertThat(r.get("crc32")).isEqualTo("99E8E009");
        assertThat(r.get("season")).isNull();
        assertThat(r.get("episode_title")).isNull();
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

    @Test
    void persepolis_groupBracketDigitsDontMakeSeasonEpisode() {
        var r = Guessit.parse(
            "Movies/Persepolis (2007)/[XCT] Persepolis "
                + "[H264+Aac-128(Fr-Eng)+ST(Fr-Eng)+Ind].mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Persepolis");
        assertThat(r.get("year")).isEqualTo(2007);
        assertThat(r.get("type")).isEqualTo("movie");
        assertThat(r.get("season")).isNull();
        assertThat(r.get("episode")).isNull();
    }

    @Test
    void officeWithCountry_titleStripsBracketWrappedCountry() {
        var r = Guessit.parse(
            "Videos/Office1080/The Office  (US)  (2005) Season 2 S02 + Extras  "
                + "(1080p AMZN WEB-DL x265 HEVC 10bit AAC 2.0 LION)/"
                + "The Office  (US)  (2005) - S02E12 - The Injury  "
                + "(1080p AMZN WEB-DL x265 LION).mkv").toMap();
        assertThat(r.get("title")).isEqualTo("The Office");
        assertThat(r.get("episode_title")).isEqualTo("The Injury");
    }

    @Test
    void psychVsPsy_episodeTitleAfterEpisodeMatch() {
        var r = Guessit.parse(
            "series/Psych/Psych S02 Season 2 Complete English DVD/"
                + "Psych.S02E03.Psy.Vs.Psy.Français.srt").toMap();
        assertThat(r.get("title")).isEqualTo("Psych");
        assertThat(r.get("episode_title")).isEqualTo("Psy Vs Psy");
        assertThat(r.get("episode")).isEqualTo(3);
    }

    @Test
    void psychMillionYears_episodeTitleAfterEpisodeMatch() {
        var r = Guessit.parse(
            "series/Psych/Psych S02 Season 2 Complete English DVD/"
                + "Psych.S02E02.65.Million.Years.Off.avi").toMap();
        assertThat(r.get("title")).isEqualTo("Psych");
        assertThat(r.get("episode_title")).isEqualTo("65 Million Years Off");
    }

    @Test
    void mindField_altTitleNotConvertedWhenAdjacentMatchUnrelated() {
        var r = Guessit.parse(
            "Mind.Field.S02E06.The.Power.of.Suggestion.1440p.H264.WEBDL.Subtitles/"
                + "The Power of Suggestion - Mind Field S2 (Ep 6) "
                + "(1440p_24fps_H264-384kbit_AAC 6Ch).mp4").toMap();
        assertThat(r.get("title")).isEqualTo("The Power of Suggestion");
        assertThat(r.get("alternative_title")).isEqualTo("Mind Field");
        assertThat(r.get("episode_title")).isNull();
    }

    @Test
    void howToBeSingle_outerDirTitleWinsOverInnerWithDashPrefix() {
        var r = Guessit.parse(
            "How.To.Be.Single.2016.1080p.BluRay.x264-BLOW/"
                + "blow-how.to.be.single.2016.1080p.bluray.x264.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("How To Be Single");
        assertThat(r.get("release_group")).isEqualTo("BLOW");
        assertThat(r.get("year")).isEqualTo(2016);
    }

    @Test
    void brokeGirls_dateAtEndDoesntStealReleaseGroup() {
        var r = Guessit.parse(
            "c:\\Temp\\autosubliminal\\completed\\2 Broke Girls\\Season 01\\"
                + "2 Broke Girls - S01E01 - HDTV-720p Proper - x264 AC3 - IMMERSE - [2011-09-19].mkv").toMap();
        assertThat(r.get("title")).isEqualTo("2 Broke Girls");
        assertThat(r.get("release_group")).isEqualTo("IMMERSE");
        assertThat(r.get("episode_title")).isNull();
        assertThat(r.get("date")).isNotNull();
    }

    @Test
    void seasonNofN_chainedNofN_secondClauseIsEpisode() {
        var r = Guessit.parse("Something.Season.2of5.3of9.Ep.Title.HDTV.torrent").toMap();
        assertThat(r.get("season")).isEqualTo(2);
        assertThat(r.get("season_count")).isEqualTo(5);
        assertThat(r.get("episode")).isEqualTo(3);
        assertThat(r.get("episode_count")).isEqualTo(9);
        assertThat(r.get("episode_title")).isEqualTo("Title");
    }

    @Test
    void seasonOnlyHead_dropsTrailingWeakBeforeEpisodeWord() {
        var r = Guessit.parse("Show Name - S32-Dummy 45-Ep 6478").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat(r.get("season")).isEqualTo(32);
        assertThat(r.get("episode")).isEqualTo(6478);
        assertThat(r.get("episode_title")).isEqualTo("Dummy 45");
    }

    @Test
    void wwiis_episodeTitleUpgradedFromOuterTitlecase() {
        var r = Guessit.parse("WWIIs.Most.Daring.Raids.S01E04.Storming.Mussolinis.Island.1080p.WEB.h264-EDHD"
            + "/wwiis.most.daring.raids.s01e04.storming.mussolinis.island.1080p.web.h.264-edhd-sample.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("wwiis most daring raids");
        assertThat(r.get("episode_title")).isEqualTo("Storming Mussolinis Island");
    }

    @Test
    void filenameHoleBeforeEpisodeMarker_isTitleNotEpisodeTitle() {
        var r = Guessit.parse("Some Dummy Directory/Season 02/Some Series-E01.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Some Series");
        assertThat(r.get("season")).isEqualTo(2);
        assertThat(r.get("episode")).isEqualTo(1);
        assertThat(r.get("episode_title")).isNull();
    }

    @Test
    void filenameHoleBeforeEpisodeMarker_dedupesWithOuterTitle() {
        var r = Guessit.parse("Some Series/Season 02/Some Series-E01.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("Some Series");
        assertThat(r.get("season")).isEqualTo(2);
        assertThat(r.get("episode")).isEqualTo(1);
        assertThat(r.get("episode_title")).isNull();
    }

    @Test
    void torrentingPrefix_titleSkipsFromWord() {
        var r = Guessit.parse(
            "From [ WWW.TORRENTING.COM ] - White.Rabbit.Project.S01E08.1080p.NF.WEBRip.DD5.1.x264-ViSUM/"
                + "White.Rabbit.Project.S01E08.1080p.NF.WEBRip.DD5.1.x264-ViSUM.mkv").toMap();
        assertThat(r.get("title")).isEqualTo("White Rabbit Project");
        assertThat(r.get("website")).isEqualTo("WWW.TORRENTING.COM");
    }

    @Test
    void releaseGroupCasing_innerWinsWhenMoreValuableViaSampleTag() {
        var r = Guessit.parse(
            "WWIIs.Most.Daring.Raids.S01E04.Storming.Mussolinis.Island.1080p.WEB.h264-EDHD/"
                + "wwiis.most.daring.raids.s01e04.storming.mussolinis.island.1080p.web.h.264-edhd-sample.mkv"
        ).toMap();
        assertThat(r.get("release_group")).isEqualTo("edhd");
        assertThat(r.get("other")).isEqualTo("Sample");
    }

    @Test
    void nameOnlyOption_treatsSlashAsLiteral() {
        var opts = io.guessit.OptionsBuilder.options().nameOnly(true).build();
        var r = io.guessit.Guessit.parse("Paparazzi - Timsit/Lindon (MKV 1080p tvripHD)", opts).toMap();
        assertThat(r.get("title")).isEqualTo("Paparazzi");
        assertThat(r.get("alternative_title")).isEqualTo(java.util.List.of("Timsit", "Lindon"));
        assertThat(r.get("screen_size")).isEqualTo("1080p");
        assertThat(r.get("container")).isEqualTo("mkv");
        assertThat(r.get("source")).isEqualTo("HDTV");
    }

    @Test
    void websiteFollowedByDate_demotesToTitle() {
        var r = Guessit.parse(
            "PlayboyPlus.com_16.01.23.Eleni.Corfiate.Playboy.Romania.XXX.iMAGESET-OHRLY"
        ).toMap();
        assertThat(r.get("title")).isEqualTo("PlayboyPlus com");
        assertThat(r.get("episode_title")).isEqualTo("Eleni Corfiate Playboy Romania");
        assertThat(r.get("date")).hasToString("2023-01-16");
        assertThat(r.get("other")).isEqualTo("XXX");
        assertThat(r.get("website")).isNull();
    }

    @Test
    void palindromeTail_outerSxxExxWinsAgainstWeakInnerSeasonEpisode() {
        var r = Guessit.parse(
            "The.Messengers.2015.S01E07.1080p.WEB-DL.DD5.1.H264.Nlsubs-Q/"
                + "QoQ-sbuSLN.462.H.1.5DD.LD-BEW.p0801.70E10S.5102.sregnesseM.ehT.mkv"
        ).toMap();
        assertThat(r.get("season")).isEqualTo(1);
        assertThat(r.get("episode")).isEqualTo(7);
        assertThat(r.get("release_group")).isEqualTo("Q");
    }

    @Test
    void trailingLanguageDash_promotesToReleaseGroup() {
        var r = Guessit.parse(
            "/Finding.Carter.S02E01.Love.the.Way.You.Lie.1080p.WEB-DL.AAC2.0.H.264-NL/"
                + "LN-462.H.0.2CAA.LD-BEW.p0801.eiL.uoY.yaW.eht.evoL.10E20S.retraC.gnidniF.mkv"
        ).toMap();
        assertThat(r.get("season")).isEqualTo(2);
        assertThat(r.get("episode")).isEqualTo(1);
        assertThat(r.get("release_group")).isEqualTo("NL");
        assertThat(r.get("language")).isNull();
    }

    @Test
    void releaseGroupCasing_outerWinsWhenItHasEpisodeTitleHole() {
        var r = Guessit.parse(
            "Scrubs/SEASON-06/Scrubs.S06E09.My.Perspective.DVDRip.XviD-WAT/"
                + "scrubs.s06e09.dvdrip.xvid-wat.avi"
        ).toMap();
        assertThat(r.get("release_group")).isEqualTo("WAT");
        assertThat(r.get("episode_title")).isEqualTo("My Perspective");
    }
}
