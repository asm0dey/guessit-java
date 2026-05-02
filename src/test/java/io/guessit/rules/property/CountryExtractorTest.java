package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.lang.Country;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountryExtractorTest {
    @Test void usCountry() {
        var r = Guessit.parse("Show.S01.US.WEB-DL.mkv").toMap();
        assertEquals(new Country("US", "United States"), r.get("country"));
    }
    @Test void ukCountry() {
        var r = Guessit.parse("Show.S01.UK.HDTV.mkv").toMap();
        assertNotNull(r.get("country"));
    }
    @Test void disallowedCountryIgnored() {
        var r = Guessit.parse("Show.S01.JP.HDTV.mkv").toMap();
        assertNull(r.get("country"));
    }
    @Test void englishLanguageNotMisreadAsCountry() {
        var r = Guessit.parse("Show.S01.EN.HDTV.mkv").toMap();
        assertNull(r.get("country"));
    }
}
