package io.guessit.lang;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanguageRegistryTest {
    private final LanguageRegistry r = LanguageRegistry.instance();

    @Test
    void findsByAlpha2() {
        var l = r.find("en").orElseThrow();
        assertEquals("English", l.name());
        assertEquals("eng", l.alpha3());
    }

    @Test
    void findsByAlpha3() {
        assertEquals("French", r.find("fra").orElseThrow().name());
        assertEquals("French", r.find("fre").orElseThrow().name()); // bibliographic alias
    }

    @Test
    void findsByName() {
        assertEquals("eng", r.find("English").orElseThrow().alpha3());
    }

    @Test
    void caseInsensitive() {
        assertEquals("en", r.find("ENGLISH").orElseThrow().alpha2());
    }

    @Test
    void resolvesAliasVo() {
        var l = r.find("vo").orElseThrow();
        assertEquals("Original Version", l.name());
    }

    @Test
    void resolvesCountryAliasUk() {
        assertEquals("GB", r.findCountry("uk").orElseThrow().alpha2());
    }

    @Test
    void countryNameIsTitleCased() {
        // iso-3166-1.csv has ALL-CAPS names like "UNITED STATES"; registry should title-case them.
        var c = r.findCountry("US").orElseThrow();
        assertEquals("United States", c.name());
    }

    @Test
    void unknownReturnsEmpty() {
        assertTrue(r.find("zzz-not-a-lang").isEmpty());
    }
}
