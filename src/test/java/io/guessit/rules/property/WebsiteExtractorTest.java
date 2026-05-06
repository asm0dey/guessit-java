package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebsiteExtractorTest {
    @Test void websiteFromTld() {
        var r = Guessit.parse("www.4MovieRulz.be - Ginny Weds Sunny (2020) 1080p Hindi Proper HDRip x264 DD5.1 - 2.4GB ESub.mkv");
        assertEquals("www.4MovieRulz.be", r.website());
    }

    @Test void websiteUrl() {
        var r = Guessit.parse("https://www.4MovieRulz.be");
        assertEquals("www.4MovieRulz.be", r.website());
    }

    @Test void webDlIsNotWebsite() {
        var r = Guessit.parse("Movie.2015.WEB-DL.mkv");
        assertNull(r.website(), "WEB-DL must not match as website");
    }

    @Test void plainTvIsNotMatched() {
        var r = Guessit.parse("Some.Title.TV.mkv");
        assertNull(r.website(), "TV must not match as website");
    }
}
