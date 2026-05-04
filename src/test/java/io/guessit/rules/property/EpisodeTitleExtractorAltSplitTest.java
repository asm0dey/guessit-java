package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EpisodeTitleExtractorAltSplitTest {
    @Test void splitsTitleAndAlternativeOnDashSpaceDash() {
        var r = Guessit.parse("Echec et Mort - Hard to Kill - Steven Seagal.mkv");
        assertThat(r.title()).isEqualTo("Echec et Mort");
        assertThat(r.alternativeTitleList()).containsExactly("Hard to Kill", "Steven Seagal");
    }
    @Test void singleDashSplitsBoth() {
        var r = Guessit.parse("Lola At Your Service - Marc Dorcel.mkv");
        assertThat(r.title()).isEqualTo("Lola At Your Service");
        assertThat(r.alternativeTitleList()).containsExactly("Marc Dorcel");
    }
}
