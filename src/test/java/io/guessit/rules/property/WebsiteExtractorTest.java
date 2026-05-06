package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebsiteExtractorTest {
    @Test void websiteFromTld() {
        var r = parse("www.4MovieRulz.be - Ginny Weds Sunny (2020) 1080p Hindi Proper HDRip x264 DD5.1 - 2.4GB ESub.mkv");
        assertThat(r.website()).isEqualTo("www.4MovieRulz.be");
    }

    @Test void websiteUrl() {
        var r = parse("https://www.4MovieRulz.be");
        assertThat(r.website()).isEqualTo("www.4MovieRulz.be");
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
