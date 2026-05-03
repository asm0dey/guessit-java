package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebsiteExtractorTest {
    @Test void websiteFromTld() {
        var r = Guessit.parse("www.4MovieRulz.be - Ginny Weds Sunny (2020) 1080p Hindi Proper HDRip x264 DD5.1 - 2.4GB ESub.mkv").toMap();
        assertEquals("www.4MovieRulz.be", r.get("website"));
    }

    @Test void websiteUrl() {
        var r = Guessit.parse("https://www.4MovieRulz.be").toMap();
        assertEquals("www.4MovieRulz.be", r.get("website"));
    }

    @Test void webDlIsNotWebsite() {
        var r = Guessit.parse("Movie.2015.WEB-DL.mkv").toMap();
        assertNull(r.get("website"), "WEB-DL must not match as website");
    }

    @Test void plainTvIsNotMatched() {
        var r = Guessit.parse("Some.Title.TV.mkv").toMap();
        assertNull(r.get("website"), "TV must not match as website");
    }
}
