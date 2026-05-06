package io.guessit.rules.property;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static org.assertj.core.api.Assertions.assertThat;

class EpisodeTitleExtractorAltSplitTest {
    @Test void splitsTitleAndAlternativeOnDashSpaceDash() {
        var r = parse("Echec et Mort - Hard to Kill - Steven Seagal.mkv");
        Assertions.assertThat(r.title()).isEqualTo("Echec et Mort");
        assertThat(r.alternativeTitleList()).containsExactly("Hard to Kill", "Steven Seagal");
    }
    @Test void singleDashSplitsBoth() {
        var r = parse("Lola At Your Service - Marc Dorcel.mkv");
        Assertions.assertThat(r.title()).isEqualTo("Lola At Your Service");
        assertThat(r.alternativeTitleList()).containsExactly("Marc Dorcel");
    }
}
