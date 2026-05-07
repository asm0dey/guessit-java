package io.guessit.lang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRegistryTest {
    private final LanguageRegistry r = LanguageRegistry.instance();

    @Test
    void findsByAlpha2() {
        var l = r.find("en").orElseThrow();
        assertThat(l.name()).isEqualTo("English");
        assertThat(l.alpha3()).isEqualTo("eng");
    }

    @Test
    void findsByAlpha3() {
        assertThat(r.find("fra").orElseThrow().name()).isEqualTo("French");
        assertThat(r.find("fre").orElseThrow().name()).isEqualTo("French");
    }

    @Test
    void findsByName() {
        assertThat(r.find("English").orElseThrow().alpha3()).isEqualTo("eng");
    }

    @Test
    void caseInsensitive() {
        assertThat(r.find("ENGLISH").orElseThrow().alpha2()).isEqualTo("en");
    }

    @Test
    void resolvesAliasVo() {
        var l = r.find("vo").orElseThrow();
        assertThat(l.name()).isEqualTo("Original Version");
    }

    @Test
    void resolvesCountryAliasUk() {
        assertThat(r.findCountry("uk").orElseThrow().alpha2()).isEqualTo("GB");
    }

    @Test
    void countryNameIsTitleCased() {
        // iso-3166-1.csv has ALL-CAPS names like "UNITED STATES"; registry should title-case them.
        var c = r.findCountry("US").orElseThrow();
        assertThat(c.name()).isEqualTo("United States");
    }

    @Test
    void unknownReturnsEmpty() {
        assertThat(r.find("zzz-not-a-lang")).isEmpty();
    }
}
