package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceExtractorTest {
    @Test void bluRayRip() {
        var r = Guessit.parse("Movie.2015.BDRip.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
        assertEquals("Rip", r.get("other"));
    }
    @Test void webDl() {
        var r = Guessit.parse("Movie.2015.WEB-DL.mkv").toMap();
        assertEquals("Web", r.get("source"));
        assertNull(r.get("other"));
    }
    @Test void webRip() {
        var r = Guessit.parse("Movie.2015.WEBRip.mkv").toMap();
        assertEquals("Web", r.get("source"));
        assertEquals("Rip", r.get("other"));
    }
    @Test void hdtv() {
        var r = Guessit.parse("Show.S01E02.HDTV.mkv").toMap();
        assertEquals("HDTV", r.get("source"));
    }
    @Test void dvdRip() {
        var r = Guessit.parse("Movie.2015.DVDRip.mkv").toMap();
        assertEquals("DVD", r.get("source"));
        assertEquals("Rip", r.get("other"));
    }
    @Test void blurayWordSpelling() {
        var r = Guessit.parse("Movie.2015.BluRay.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
    }
    @Test void plainTvIsNotMatched() {
        var r = Guessit.parse("Some.Title.TV.mkv").toMap();
        assertNull(r.get("source"), "raw TV without rip context must not match");
    }
    @Test void brripBecomesBluray() {
        var r = Guessit.parse("Movie.2015.BRRip.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
    }
    @Test void hdcam() {
        var r = Guessit.parse("Movie.2015.HDCAM.mkv").toMap();
        assertEquals("HD Camera", r.get("source"));
    }

    @Test void weakWebRemovedWhenStrongSourceFollows() {
        var r = Guessit.parse("Some.WEB.Title.2015.BluRay.mkv").toMap();
        assertEquals("Blu-ray", r.get("source"));
    }

    @Test void weakWebKeptWhenAlone() {
        var r = Guessit.parse("Movie.2015.WEB.mkv").toMap();
        assertEquals("Web", r.get("source"));
    }

    @Test void ultraHdBluray2160p() {
        var r = Guessit.parse("Movie.2015.2160p.BluRay.mkv").toMap();
        assertEquals("Ultra HD Blu-ray", r.get("source"));
        assertEquals("2160p", r.get("screen_size"));
    }
}
