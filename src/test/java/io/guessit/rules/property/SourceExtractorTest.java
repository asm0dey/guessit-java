package io.guessit.rules.property;

import io.guessit.GuessResult;
import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class SourceExtractorTest {
    @Test void bluRayRip() {
        GuessResult r = Guessit.parse("Movie.2015.BDRip.mkv");
        assertThat(r.source()).isEqualTo("Blu-ray");
        assertThat(r.other()).isEqualTo(List.of("Rip"));
    }
    @Test void webDl() {
        GuessResult r = Guessit.parse("Movie.2015.WEB-DL.mkv");
        assertThat(r.source()).isEqualTo("Web");
        assertThat(r.other()).isNull();
    }
    @Test void webRip() {
        GuessResult r = Guessit.parse("Movie.2015.WEBRip.mkv");
        assertThat(r.source()).isEqualTo("Web");
        assertThat("Rip").isIn(r.other());
    }
    @Test void hdtv() {
        GuessResult r = Guessit.parse("Show.S01E02.HDTV.mkv");
        assertThat(r.source()).isEqualTo("HDTV");
    }
    @Test void dvdRip() {
        GuessResult r = Guessit.parse("Movie.2015.DVDRip.mkv");
        assertThat(r.source()).isEqualTo("DVD");
        assertThat(r.other()).isEqualTo(List.of("Rip"));
    }
    @Test void blurayWordSpelling() {
        GuessResult r = Guessit.parse("Movie.2015.BluRay.mkv");
        assertThat(r.source()).isEqualTo("Blu-ray");
    }
    @Test void plainTvIsNotMatched() {
        GuessResult r = Guessit.parse("Some.Title.TV.mkv");
        assertThat(r.source()).as("raw TV without rip context must not match").isNull();
    }
    @Test void brripBecomesBluray() {
        GuessResult r = Guessit.parse("Movie.2015.BRRip.mkv");
        assertThat(r.source()).isEqualTo("Blu-ray");
    }
    @Test void hdcam() {
        GuessResult r = Guessit.parse("Movie.2015.HDCAM.mkv");
        assertThat(r.source()).isEqualTo("HD Camera");
    }

    @Test void weakWebRemovedWhenStrongSourceFollows() {
        GuessResult r = Guessit.parse("Some.WEB.Title.2015.BluRay.mkv");
        assertThat(r.source()).isEqualTo("Blu-ray");
    }

    @Test void weakWebKeptWhenAlone() {
        GuessResult r = Guessit.parse("Movie.2015.WEB.mkv");
        assertThat(r.source()).isEqualTo("Web");
    }

    @Test void ultraHdBluray2160p() {
        GuessResult r = Guessit.parse("Movie.2015.2160p.BluRay.mkv");
        assertThat(r.source()).isEqualTo("Ultra HD Blu-ray");
        assertThat(r.screenSize()).isEqualTo("2160p");
    }

    @Test void hdTsExtensionNotSource() {
        GuessResult parse = Guessit.parse("Hd.Ts");
        assertThat(parse.container()).isEqualTo("ts");
        assertThat(parse.source()).isNull();
    }

    @Test void editionCcDroppedWhenStreamingServiceConsumesIt() {
        GuessResult r = Guessit.parse(
                "Show.Name.2016.09.28.Nice.Title.Extended.1080p.CC.WEBRip.AAC2.0.x264-monkee");
        assertThat(r.edition()).isEqualTo(List.of("Extended"));
        assertThat(r.streamingService()).isEqualTo("Comedy Central");
    }

    @Test void editionCcKeptWhenStandalone() {
        GuessResult r = Guessit.parse("CC");
        assertThat(r.edition()).isEqualTo(List.of("Criterion"));
    }
}
