package io.guessit.rules.property;

import io.guessit.Guessit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AbsoluteEpisodeTest {
    @Test void bleachTwoGroupsHigherBecomesAbsolute() {
        var r = Guessit.parse("Bleach - s16e03-04 - 313-314");
        assertThat(r.episodeList()).containsExactlyInAnyOrder(3, 4);
        assertThat(r.field("absolute_episode")).isEqualTo(java.util.List.of(313, 314));
    }
    @Test void hatsuyukiAnimeRangeWithParensAbsolute() {
        var r = Guessit.parse("[Hatsuyuki-Kaitou]_Fairy_Tail_2_-_16-20_(191-195)_[720p][10bit].torrent");
        assertThat(r.episodeList()).contains(16, 17, 18, 19, 20);
        assertThat(r.field("absolute_episode")).isEqualTo(java.util.List.of(191, 192, 193, 194, 195));
    }
    @Test void absoluteEpisodeNotEmittedForMovieTitleNumber() {
        assertThat(Guessit.parse("12.Monkeys.1995.mkv").field("absolute_episode")).isNull();
        assertThat(Guessit.parse("24.S01E01.mkv").field("absolute_episode")).isNull();
    }
}
