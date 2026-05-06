package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.lang.Language;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageExtractorTest {

    public static final String ENGLISH = "English";

    @Test void englishAlpha2() {
        var r = Guessit.parse("Show.S01.ENG.HDTV.mkv");
        assertThat(r.language().getFirst()).isEqualTo(new Language("en", "eng", ENGLISH));
    }
    @Test void multipleLanguagesCollapseToList() {
        var r = Guessit.parse("Movie.2015.ENG.FRE.BluRay.mkv");
        var languages = r.language();
        assertNotNull(languages);
        assertThat(languages).hasSize(2);
    }
    @Test void undeterminedDroppedWhenRealLangPresent() {
        var r = Guessit.parse("Show.UND.ENG.HDTV.mkv");
        assertThat(r.language().getFirst()).isEqualTo(new Language("en", "eng", ENGLISH));
    }
    @Test void subtitlePrefixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.VOST.ENG.HDTV.mkv");
        assertNull(r.language());
        assertThat(r.subtitleLanguage().getFirst()).isEqualTo(new Language("en", "eng", ENGLISH));
    }
    @Test void subtitleSuffixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.ENG.SUB.HDTV.mkv");
        assertNull(r.language());
        assertThat(r.subtitleLanguage().getFirst()).isEqualTo(new Language("en", "eng", ENGLISH));
    }
    @Test void subtitleExtensionPromotesPreviousLanguage() {
        var r = Guessit.parse("Show.S01.ENG.srt");
        assertNull(r.language());
        assertThat(r.subtitleLanguage().getFirst()).isEqualTo(new Language("en", "eng", ENGLISH));
    }
}
