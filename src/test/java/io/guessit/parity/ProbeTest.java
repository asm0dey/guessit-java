package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import io.guessit.lang.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.guessit.OptionsBuilder.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted regression checks for YML parity diffs that have been fixed.
 * New diagnoses go here first to anchor a fix; once a parity case lands
 * the assertion stays as a guard.
 */
@SuppressWarnings("unchecked")
class ProbeTest {

    @Test
    void dateSeries_titleKeepsSeries() {
        var r = Guessit.parse("Date.Series.10-11-2008.XViD");
        assertThat(r.title()).isEqualTo("Date Series");
        assertThat(r.date()).isNotNull();
    }

    @Test
    void epiTypeEpisode_titleEpi() {
        var r = Guessit.parse("epi", options().type("episode").build());
        assertThat(r.title()).isEqualTo("epi");
    }

    @Test
    void flexgetSeries() {
        var r = Guessit.parse("FlexGet.Series.2013.14.of.21.Title.Here.720p.HDTV.AAC5.1.x264-NOGRP");
        assertThat(r.title()).isEqualTo("FlexGet Series");
    }

    @Test
    void fumetsuNoAnataE_keepsTrailingE() {
        var r = Guessit.parse("[Erai-raws] Fumetsu no Anata e - 03 [720p][Multiple Subtitle].mkv");
        assertThat(r.title()).isEqualTo("Fumetsu no Anata e");
    }

    @Test
    void darkKnight_hqStaysOther_audioProfileAdjacencyRule() {
        var r = Guessit.parse(
                "The.Dark.Knight.IMAX.EDITION.HQ.BluRay.1080p.x264.AC3.Hindi.Eng.ETRG");
        assertThat(r.other()).containsOnly("High Quality");
        assertThat(r.audioProfile()).isNull();
    }

    @Test
    void sxxAll_yieldsSeasonAndCompleteOther() {
        var r = Guessit.parse("Something.1xAll-FlexGet");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.other()).containsOnly("Complete");
        assertThat(r.releaseGroup()).isEqualTo("FlexGet");
    }

    @Test
    void rangeFiller_acrossSxxExxPair() {
        var r = Guessit.parse("My.Name.Is.Earl.S01E01-S01E21.SWE-SUB");
        var ep = r.episodeList();
        assertThat(ep).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21);
    }

    @Test
    void numXNumWithSpaces_seasonEpisodePair() {
        var r = Guessit.parse("Something 1 x 2-FlexGet",
                options().type("episode").build());
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(2);
    }

    @Test
    void commeUneImage_outerFolderProperCase() {
        var r = Guessit.parse(
                "Movies/Comme une Image (2004)/Comme.Une.Image.FRENCH.DVDRiP.XViD-NTK.par-www.divx-overnet.com.avi");
        assertThat(r.title()).isEqualTo("Comme une Image");
    }

    @Test
    void duckman101Parens_keepsCompactSsee() {
        var r = Guessit.parse(
                "Series/Duckman/Duckman - 101 (01) - 20021107 - I, Duckman.avi");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episode()).isEqualTo(1);
        assertThat(r.title()).isEqualTo("Duckman");
        assertThat(r.episodeTitle()).isEqualTo("I, Duckman");
    }

    @Test
    void preferTitleWithYear_tagsEquivalentIgnore() {
        // The kept title must carry "equivalent-ignore" so EquivalentHoles
        // doesn't replace its outer-folder value with the filename's
        // title-cased hole.
        var r = Guessit.parse("Foo Bar (2010)/foo.bar.2010.mkv");
        assertThat(r.title()).isEqualTo("Foo Bar");
    }

    @Test
    void subsAfterSubtitleLang_trailingDashRg() {
        var r1 = Guessit.parse("Show.Name.S01E02.HDTV.x264.NL-subs-ABC");
        assertThat(r1.subtitleLanguage()).isNotNull();
        assertThat(r1.releaseGroup()).isEqualTo("ABC");

        var r2 = Guessit.parse(
                "FROZEN [2010] LiMiTED DVDRip H262 AAC[ ENG SUBS]-MANTESH");
        assertThat(r2.releaseGroup()).isEqualTo("MANTESH");
    }

    @Test
    void subsDashRg_undSubtitleAndTrailingRg() {
        var r1 = Guessit.parse(
                "Life of Pi 2012 2160p 4K BluRay HDR10 HEVC BT2020 DTSHD 7.1 subs -DDR");
        assertThat(r1.subtitleLanguage()).isNotNull();
        assertThat(r1.releaseGroup()).isEqualTo("DDR");

        var r2 = Guessit.parse("Family.Katta.2016.1080p.WEB-DL.H263.DD5.1.ESub-DDR");
        assertThat(r2.subtitleLanguage()).isNotNull();
        assertThat(r2.releaseGroup()).isEqualTo("DDR");
    }

    @Test
    void mbcVodEmbedded_streamingService() {
        var r = Guessit.parse(
                "Eyes.Of.Dawn.1991.E01.480p.MBCVOD.AAC.x264-NOGPR.mp4");
        assertThat(r.streamingService()).isEqualTo("MBC");
    }

    @Test
    void chuckBerry320Kbps_typeMovie_noEpisode() {
        var r = Guessit.parse("Chuck Berry The Very Best Of Chuck Berry(2010)[320 Kbps]");
        assertThat(r.type()).isEqualTo("movie");
        assertThat(r.year()).isEqualTo(2010);
        assertThat(r.audioBitRate()).isNotNull();
        assertThat(r.season()).isNull();
        assertThat(r.episode()).isNull();
    }

    @Test
    void epWordBeforeSxxExx_excludedFromTitle() {
        var r = Guessit.parse("Star Trek DS9 Ep 2x03 The Siege (Part III)");
        assertThat(r.title()).isEqualTo("Star Trek DS9");
        assertThat(r.season()).isEqualTo(2);
        assertThat(r.episode()).isEqualTo(3);
    }

    @Test
    void s01e01e07_fooBarGroup_uuidNotSwallowsEpisodeTitle() {
        var r = Guessit.parse("Test.S01E01E07-FooBar-Group.avi");
        assertThat(r.episodeTitle()).isEqualTo("FooBar-Group");
        var extras = r.extras();
        assertThat(extras == null ? null : extras.get("uuid")).isNull();
        assertThat(r.episodeList()).isEqualTo(List.of(1, 7));
    }

    @Test
    void showE02v2_versionAfterEpisode() {
        var r = Guessit.parse("Show.E02v2.mkv");
        assertThat(r.episode()).isEqualTo(2);
        assertThat(r.version()).isEqualTo(2);
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
        var r = Guessit.parse("Show.Name.-.476-479.(2007).[HorribleSubs][WEBRip]..[HD.720p]");
        assertThat(r.episodeList()).containsExactlyInAnyOrder(476, 477, 478, 479);
        assertThat(r.title()).isEqualTo("Show Name");
    }

    @Test
    void showName313To315_dotsAbsoluteEpisode() {
        var r = Guessit.parse("Show.Name.313-315.s16e03-05");
        assertThat(r.title()).isEqualTo("Show Name");
        var extras = r.extras();
        assertThat(extras == null ? null : (java.util.List<Integer>) extras.get("absolute_episode")).containsExactlyInAnyOrder(313, 314, 315);
        assertThat(r.season()).isEqualTo(16);
    }

    @Test
    void showName313To315_spacesAbsoluteEpisode() {
        var r = Guessit.parse("Show Name - 313-315 - s16e03-05");
        assertThat(r.title()).isEqualTo("Show Name");
        var extras = r.extras();
        assertThat(extras == null ? null : (java.util.List<Integer>) extras.get("absolute_episode")).containsExactlyInAnyOrder(313, 314, 315);
        assertThat(r.season()).isEqualTo(16);
    }

    @Test
    void enInsideEpisodeTitle_notLanguage() {
        var r = Guessit.parse(
                "Fear the Walking Dead - 01x02 - En Close, Yet En Far.REPACK-KILLERS.French.C.updated.Addic7ed.com.mkv");
        assertThat(r.episodeTitle()).isEqualTo("En Close, Yet En Far");
        var lang = r.language();
        if (lang instanceof java.util.List<?> l) {
            assertThat(l).extracting(o -> ((Language) o).alpha2()).isNotEmpty().doesNotContain("en");
        }
    }

    @Test
    void s01Extras_seasonAndOtherExtras() {
        var r = Guessit.parse("Series/My Name Is Earl/My.Name.Is.Earl.S01Extras.-.Bad.Karma.DVDRip.XviD.avi");
        assertThat(r.title()).isEqualTo("My Name Is Earl");
        assertThat(r.season()).isEqualTo(1);
        assertThat(r.episodeTitle()).isEqualTo("Bad Karma");
        var other = r.other();
        if (other instanceof java.util.List<?> o) {
            assertThat((List<String>) o).contains("Extras");
        }
    }

    @Test
    void xctLePrestige_chapsReleaseGroup() {
        var r = Guessit.parse(
                "[XCT].Le.Prestige.(The.Prestige).DVDRip.[x264.HP.He-Aac.{Fr-Eng}.St{Fr-Eng}.Chaps].mkv");
        assertThat(r.title()).isEqualTo("Le Prestige");
        assertThat(r.releaseGroup()).isEqualTo("Chaps");
    }

    @Test
    void showName2_10_2016_animeContextKeepsWeakEpisode() {
        var r = Guessit.parse("Show!.Name.2.-.10.(2016).[HorribleSubs][WEBRip]..[HD.720p]");
        assertThat(r.episode()).isEqualTo(10);
        assertThat(r.year()).isEqualTo(2016);
        assertThat(r.type()).isEqualTo("episode");
        assertThat(r.releaseGroup()).isEqualTo("HorribleSubs");
    }

    @Test
    void dimensionWTvDub_undLanguageAndTvSource() {
        var r = Guessit.parse("[MK-Pn8].Dimension.W.-.05.[720p][Hi10][Dual][TV-Dub][EDA6E7F1]",
                OptionsBuilder.options()
                        .allowedLanguages(List.of("und"))
                        .allowedCountries(List.of("us"))
                        .build());
        assertThat(r.source()).isEqualTo("TV");
        assertThat(r.language()).isNotNull();
    }

    @Test
    void splitScenesBetweenSourceAndContainer_notAltTitle() {
        var r = Guessit.parse(
                "French Maid Services - Lola At Your Service WEB-DL SPLIT SCENES MP4-RARBG");
        assertThat(r.title()).isEqualTo("French Maid Services");
        assertThat(r.alternativeTitleList()).isEqualTo(List.of("Lola At Your Service"));
        assertThat(r.releaseGroup()).isEqualTo("RARBG");
    }

    @Test
    void subInGroup_promotesAllConsecutiveLanguagesAfter() {
        var r = Guessit.parse(
                "Show Name S01e10[Mux - 1080p - H264 - Ita Eng Ac3 - Sub Ita Eng]DLMux By GiuseppeTnT Littlelinx");
        var subs = r.subtitleLanguage();
        if (subs instanceof java.util.List<?> subsList) {
            assertThat(subsList).extracting(o -> ((Language) o).alpha3()).containsExactlyInAnyOrder("ita", "eng");
        }
    }

    @Test
    void barFoodChristmasSpecial_episodeTypeViaSpecialDetail() {
        var r = Guessit.parse("BarFood christmas special HDTV",
                options().expectedTitle(List.of("BarFood")).build());
        assertThat(r.title()).isEqualTo("BarFood");
        assertThat(r.episodeTitle()).isEqualTo("christmas special");
        assertThat(r.type()).isEqualTo("episode");
        var extras = r.extras();
        assertThat(extras == null ? null : extras.get("episode_details")).isEqualTo("Special");
    }

    @Test
    void queenAKindOfMagic_2cd_noCdAltTitle() {
        var r = Guessit.parse("Queen - A Kind of Magic (Alternative Extended Version) 2CD 2014");
        assertThat(r.title()).isEqualTo("Queen");
        assertThat(r.alternativeTitleList()).isEqualTo(List.of("A Kind of Magic"));
        assertThat(r.cdCount()).isEqualTo(2);
    }

    @Test
    void germanCustomSubbed_subtitleLanguage() {
        var r = Guessit.parse(
                "Show.Name.S04E21.Aint.Nothing.Like.the.Real.Thing.German.Custom.Subbed.720p.HDTV.x264.iNTERNAL-BaCKToRG");
        assertThat(r.subtitleLanguage()).isNotNull();
        assertThat(r.language()).isNull();
        assertThat(r.episodeTitle()).isEqualTo("Aint Nothing Like the Real Thing");
    }

    @Test
    void huaMulan_hrAsReleaseGroup() {
        var r = Guessit.parse("Hua.Mulan.BRRIP.MP4.x264.720p-HR.avi");
        assertThat(r.title()).isEqualTo("Hua Mulan");
        assertThat(r.releaseGroup()).isEqualTo("HR");
        var other = r.other();
        if (other instanceof java.util.List<?> o) {
            assertThat((List<String>) o).isNotEmpty().doesNotContain("High Resolution");
        }
    }

    @Test
    void waveyObfuscated_titleNotReleaseGroup() {
        var r = Guessit.parse("Season 06/e01.1080p.bluray.x264-wavey-obfuscated.mkv");
        assertThat(r.title()).isEqualTo("wavey");
        assertThat(r.releaseGroup()).isNull();
        assertThat(r.other()).first().isEqualTo("Obfuscated");
    }

    @Test
    void epsilonXpost_keepsReleaseGroupWhenLeadingTitleExists() {
        var r = Guessit.parse(
                "24.S01E02.1080p.BluRay.REMUX.AVC.DD.2.0-EPSiLON-xpost/eb518eaf33f641a1a8c6e0973a67aec2.mkv");
        assertThat(r.title()).isEqualTo("24");
        assertThat(r.releaseGroup()).isEqualTo("EPSiLON");
    }

    @Test
    void engineeringCatastrophes_wholeFilepartGroupKeepsTitle() {
        var r = Guessit.parse("[ Engineering Catastrophes S02E10 1080p AMZN WEB-DL DD+ 2.0 x264-TrollHD ]");
        assertThat(r.title()).isEqualTo("Engineering Catastrophes");
        assertThat(r.season()).isEqualTo(2);
        assertThat(r.episode()).isEqualTo(10);
        assertThat(r.releaseGroup()).isEqualTo("TrollHD");
    }

    @Test
    void daiseiFreeIwatobiCrcSwallowsSxxExx() {
        var r = Guessit.parse("[Daisei] Free!：Iwatobi Swim Club - 01 ~ (BD 720p 10-bit AAC) [99E8E009].mkv");
        assertThat(r.title()).isEqualTo("Free!：Iwatobi Swim Club");
        assertThat(r.episode()).isEqualTo(1);
        assertThat(r.crc32()).isEqualTo("99E8E009");
        assertThat(r.season()).isNull();
        assertThat(r.episodeTitle()).isNull();
    }

    @Test
    void insider_bonusTitleKeepsLeadingNumber() {
        var r = Guessit.parse("The_Insider-(1999)-x02-60_Minutes_Interview-1996.mp4");
        assertThat(r.title()).isEqualTo("The Insider");
        assertThat(r.year()).isEqualTo(1999);
        assertThat(r.bonus()).isEqualTo(2);
        assertThat(r.bonusTitle()).isEqualTo("60 Minutes Interview-1996");
        assertThat(r.alternativeTitleList()).isNull();
    }

    @Test
    void persepolis_groupBracketDigitsDontMakeSeasonEpisode() {
        var r = Guessit.parse(
                "Movies/Persepolis (2007)/[XCT] Persepolis "
                        + "[H264+Aac-128(Fr-Eng)+ST(Fr-Eng)+Ind].mkv");
        assertThat(r.title()).isEqualTo("Persepolis");
        assertThat(r.year()).isEqualTo(2007);
        assertThat(r.type()).isEqualTo("movie");
        assertThat(r.season()).isNull();
        assertThat(r.episode()).isNull();
    }

    @Test
    void officeWithCountry_titleStripsBracketWrappedCountry() {
        var r = Guessit.parse(
                "Videos/Office1080/The Office  (US)  (2005) Season 2 S02 + Extras  "
                        + "(1080p AMZN WEB-DL x265 HEVC 10bit AAC 2.0 LION)/"
                        + "The Office  (US)  (2005) - S02E12 - The Injury  "
                        + "(1080p AMZN WEB-DL x265 LION).mkv");
        assertThat(r.title()).isEqualTo("The Office");
        assertThat(r.episodeTitle()).isEqualTo("The Injury");
    }

    @Test
    void psychVsPsy_episodeTitleAfterEpisodeMatch() {
        var r = Guessit.parse(
                "series/Psych/Psych S02 Season 2 Complete English DVD/"
                        + "Psych.S02E03.Psy.Vs.Psy.Français.srt");
        assertThat(r.title()).isEqualTo("Psych");
        assertThat(r.episodeTitle()).isEqualTo("Psy Vs Psy");
        assertThat(r.episode()).isEqualTo(3);
    }

    @Test
    void psychMillionYears_episodeTitleAfterEpisodeMatch() {
        var r = Guessit.parse(
                "series/Psych/Psych S02 Season 2 Complete English DVD/"
                        + "Psych.S02E02.65.Million.Years.Off.avi");
        assertThat(r.title()).isEqualTo("Psych");
        assertThat(r.episodeTitle()).isEqualTo("65 Million Years Off");
    }

    @Test
    void mindField_altTitleNotConvertedWhenAdjacentMatchUnrelated() {
        var r = Guessit.parse(
                "Mind.Field.S02E06.The.Power.of.Suggestion.1440p.H264.WEBDL.Subtitles/"
                        + "The Power of Suggestion - Mind Field S2 (Ep 6) "
                        + "(1440p_24fps_H264-384kbit_AAC 6Ch).mp4");
        assertThat(r.title()).isEqualTo("The Power of Suggestion");
        assertThat(r.alternativeTitleList()).isEqualTo(List.of("Mind Field"));
        assertThat(r.episodeTitle()).isNull();
    }

    @Test
    void howToBeSingle_outerDirTitleWinsOverInnerWithDashPrefix() {
        var r = Guessit.parse(
                "How.To.Be.Single.2016.1080p.BluRay.x264-BLOW/"
                        + "blow-how.to.be.single.2016.1080p.bluray.x264.mkv");
        assertThat(r.title()).isEqualTo("How To Be Single");
        assertThat(r.releaseGroup()).isEqualTo("BLOW");
        assertThat(r.year()).isEqualTo(2016);
    }

    @Test
    void brokeGirls_dateAtEndDoesntStealReleaseGroup() {
        var r = Guessit.parse(
                "c:\\Temp\\autosubliminal\\completed\\2 Broke Girls\\Season 01\\"
                        + "2 Broke Girls - S01E01 - HDTV-720p Proper - x264 AC3 - IMMERSE - [2011-09-19].mkv");
        assertThat(r.title()).isEqualTo("2 Broke Girls");
        assertThat(r.releaseGroup()).isEqualTo("IMMERSE");
        assertThat(r.episodeTitle()).isNull();
        assertThat(r.date()).isNotNull();
    }

    @Test
    void seasonNofN_chainedNofN_secondClauseIsEpisode() {
        var r = Guessit.parse("Something.Season.2of5.3of9.Ep.Title.HDTV.torrent");
        assertThat(r.season()).isEqualTo(2);
        assertThat(r.seasonCount()).isEqualTo(5);
        assertThat(r.episode()).isEqualTo(3);
        assertThat(r.episodeCount()).isEqualTo(9);
        assertThat(r.episodeTitle()).isEqualTo("Title");
    }

    @Test
    void seasonOnlyHead_dropsTrailingWeakBeforeEpisodeWord() {
        var r = Guessit.parse("Show Name - S32-Dummy 45-Ep 6478");
        assertThat(r.title()).isEqualTo("Show Name");
        assertThat(r.season()).isEqualTo(32);
        assertThat(r.episode()).isEqualTo(6478);
        assertThat(r.episodeTitle()).isEqualTo("Dummy 45");
    }

    @Test
    void wwiis_episodeTitleUpgradedFromOuterTitlecase() {
        var r = Guessit.parse("WWIIs.Most.Daring.Raids.S01E04.Storming.Mussolinis.Island.1080p.WEB.h264-EDHD"
                + "/wwiis.most.daring.raids.s01e04.storming.mussolinis.island.1080p.web.h.264-edhd-sample.mkv");
        assertThat(r.title()).isEqualTo("wwiis most daring raids");
        assertThat(r.episodeTitle()).isEqualTo("Storming Mussolinis Island");
    }

    @Test
    void filenameHoleBeforeEpisodeMarker_isTitleNotEpisodeTitle() {
        var r = Guessit.parse("Some Dummy Directory/Season 02/Some Series-E01.mkv");
        assertThat(r.title()).isEqualTo("Some Series");
        assertThat(r.season()).isEqualTo(2);
        assertThat(r.episode()).isEqualTo(1);
        assertThat(r.episodeTitle()).isNull();
    }

    @Test
    void filenameHoleBeforeEpisodeMarker_dedupesWithOuterTitle() {
        var r = Guessit.parse("Some Series/Season 02/Some Series-E01.mkv");
        assertThat(r.title()).isEqualTo("Some Series");
        assertThat(r.season()).isEqualTo(2);
        assertThat(r.episode()).isEqualTo(1);
        assertThat(r.episodeTitle()).isNull();
    }

    @Test
    void torrentingPrefix_titleSkipsFromWord() {
        var r = Guessit.parse(
                "From [ WWW.TORRENTING.COM ] - White.Rabbit.Project.S01E08.1080p.NF.WEBRip.DD5.1.x264-ViSUM/"
                        + "White.Rabbit.Project.S01E08.1080p.NF.WEBRip.DD5.1.x264-ViSUM.mkv");
        assertThat(r.title()).isEqualTo("White Rabbit Project");
        assertThat(r.website()).isEqualTo("WWW.TORRENTING.COM");
    }

    @Test
    void releaseGroupCasing_innerWinsWhenMoreValuableViaSampleTag() {
        var r = Guessit.parse(
                "WWIIs.Most.Daring.Raids.S01E04.Storming.Mussolinis.Island.1080p.WEB.h264-EDHD/"
                        + "wwiis.most.daring.raids.s01e04.storming.mussolinis.island.1080p.web.h.264-edhd-sample.mkv"
        );
        assertThat(r.releaseGroup()).isEqualTo("edhd");
        assertThat(r.other()).containsOnly("Sample");
    }

    @Test
    void nameOnlyOption_treatsSlashAsLiteral() {
        var opts = io.guessit.OptionsBuilder.options().nameOnly(true).build();
        var r = io.guessit.Guessit.parse("Paparazzi - Timsit/Lindon (MKV 1080p tvripHD)", opts);
        assertThat(r.title()).isEqualTo("Paparazzi");
        assertThat(r.alternativeTitleList()).isEqualTo(java.util.List.of("Timsit", "Lindon"));
        assertThat(r.screenSize()).isEqualTo("1080p");
        assertThat(r.container()).isEqualTo("mkv");
        assertThat(r.source()).isEqualTo("HDTV");
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
    void multipleSourceMatches_emitAsList() {
        var r = Guessit.parse("dvd ts").toMap();
        assertThat(r.get("source")).isEqualTo(java.util.List.of("DVD", "Telesync"));
    }

    @Test
    void multiplePartMatches_emitAsList() {
        var r = Guessit.parse("Show.Name.Part.1.and.Part.2.Blah-Group").toMap();
        assertThat(r.get("title")).isEqualTo("Show Name");
        assertThat(r.get("part")).isEqualTo(java.util.List.of(1, 2));
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
