package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.lang.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LanguageExtractorTest {

    public static final String LANGUAGE = "language";
    public static final String ENGLISH = "English";

    @Test void englishAlpha2() {
        var r = Guessit.parse("Show.S01.ENG.HDTV.mkv").toMap();
        assertEquals(new Language("en", "eng", ENGLISH), r.get(LANGUAGE));
    }
    @Test void multipleLanguagesCollapseToList() {
        var r = Guessit.parse("Movie.2015.ENG.FRE.BluRay.mkv").toMap();
        var languages = (List<?>) r.get(LANGUAGE);
        assertNotNull(languages);
        assertEquals(2, languages.size());
    }
    @Test void undeterminedDroppedWhenRealLangPresent() {
        var r = Guessit.parse("Show.UND.ENG.HDTV.mkv").toMap();
        assertEquals(new Language("en", "eng", ENGLISH), r.get(LANGUAGE));
    }
    @Test void subtitlePrefixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.VOST.ENG.HDTV.mkv").toMap();
        assertNull(r.get(LANGUAGE));
        assertEquals(new Language("en", "eng", ENGLISH), r.get("subtitle_language"));
    }
    @Test void subtitleSuffixConvertsLanguageToSubtitle() {
        var r = Guessit.parse("Show.S01.ENG.SUB.HDTV.mkv").toMap();
        assertNull(r.get(LANGUAGE));
        assertEquals(new Language("en", "eng", ENGLISH), r.get("subtitle_language"));
    }
    @Test void subtitleExtensionPromotesPreviousLanguage() {
        var r = Guessit.parse("Show.S01.ENG.srt").toMap();
        assertNull(r.get(LANGUAGE));
        assertEquals(new Language("en", "eng", ENGLISH), r.get("subtitle_language"));
    }
}
