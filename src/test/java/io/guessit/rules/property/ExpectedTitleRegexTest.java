package io.guessit.rules.property;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExpectedTitleRegexTest {
    @Test void reColonAcceptsAnyMatchingTitle() {
        var opts = OptionsBuilder.options()
            .expectedTitle(List.of("re:my \\d+p show"))
            .build();
        var r = Guessit.parse("my 720p show S01E02.mkv", opts);
        assertThat(r.title()).isEqualTo("my 720p show");
    }
    @Test void literalExpectedTitleStillWorks() {
        var opts = OptionsBuilder.options()
            .expectedTitle(List.of("Show Name"))
            .build();
        var r = Guessit.parse("Show.Name.S01E02.mkv", opts);
        assertThat(r.title()).isEqualTo("Show Name");
    }
}
