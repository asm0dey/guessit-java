package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.lang.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanguageExtractorTest {

    public static final String ENGLISH = "English";

    @Test void englishAlpha2() {
        var r = Guessit.parse("Show.S01.ENG.HDTV.mkv");
        assertEquals(new Language("en", "eng", ENGLISH), r.language().getFirst());
    }
    @Test void multipleLanguagesCollapseToList() {
        var r = Guessit.parse("Movie.2015.ENG.FRE.BluRay.mkv");
        var languages = r.language();
        assertNotNull(languages);
        assertEquals(2, languages.size());
    }
    @Test void undeterminedDroppedWhenRealLangPresent() {
        var r = Guessit.parse("Show.UND.ENG.HDTV.mkv");
        assertEquals(new Language("en", "eng", ENGLISH), r.language().getFirst());
    }
    @Test void subtitlePrefixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.VOST.ENG.HDTV.mkv");
        assertNull(r.language());
        assertEquals(new Language("en", "eng", ENGLISH), r.subtitleLanguage().getFirst());
    }
    @Test void subtitleSuffixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.ENG.SUB.HDTV.mkv");
        assertNull(r.language());
        assertEquals(new Language("en", "eng", ENGLISH), r.subtitleLanguage().getFirst());
    }
    @Test void subtitleExtensionPromotesPreviousLanguage() {
        var r = Guessit.parse("Show.S01.ENG.srt");
        assertNull(r.language());
        assertEquals(new Language("en", "eng", ENGLISH), r.subtitleLanguage().getFirst());
    }
}
