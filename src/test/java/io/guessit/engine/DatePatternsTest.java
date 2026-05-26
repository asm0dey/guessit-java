package io.guessit.engine;

import io.guessit.engine.date.DatePatterns;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatePatternsTest {
    @Test void validYearRange() {
        assertTrue(DatePatterns.validYear(1920));
        assertTrue(DatePatterns.validYear(2029));
        assertFalse(DatePatterns.validYear(1919));
        assertFalse(DatePatterns.validYear(2030));
    }
    @Test void validWeekRange() {
        assertTrue(DatePatterns.validWeek(1));
        assertTrue(DatePatterns.validWeek(52));
        assertFalse(DatePatterns.validWeek(0));
        assertFalse(DatePatterns.validWeek(53));
    }
    @Test void searchYmd() {
        var r = DatePatterns.search(" Show 2002-04-22 1080p ", null, null).orElseThrow();
        assertThat(r.date()).isEqualTo(LocalDate.of(2002, 4, 22));
    }
    @Test void searchDmy() {
        var r = DatePatterns.search("And this on 17-06-1998.", null, null).orElseThrow();
        assertThat(r.date()).isEqualTo(LocalDate.of(1998, 6, 17));
    }
    @Test void searchTwoDigitYearGuessesDayFirst() {
        var r = DatePatterns.search(" e 22-04-02 e", null, null).orElseThrow();
        assertThat(r.date()).isEqualTo(LocalDate.of(2002, 4, 22));
    }
    @Test void searchYearFirstHonoured() {
        var r = DatePatterns.search(" e 02.04.22 e", true, null).orElseThrow();
        assertThat(r.date()).isEqualTo(LocalDate.of(2002, 4, 22));
    }
    @Test void noDate() {
        assertThat(DatePatterns.search(" no date ", null, null)).isEmpty();
    }
    @Test void searchYmdWithX() {
        var r = DatePatterns.search("Something.2008x12.13-FlexGet", null, null).orElseThrow();
        assertThat(r.date()).isEqualTo(LocalDate.of(2008, 12, 13));
    }
}
