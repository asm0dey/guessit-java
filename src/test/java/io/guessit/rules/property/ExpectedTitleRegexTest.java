package io.guessit.rules.property;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.Guessit.parse;
import static io.guessit.OptionsBuilder.options;
import static java.util.List.of;

class ExpectedTitleRegexTest {
    @Test void reColonAcceptsAnyMatchingTitle() {
        var opts = options()
                .expectedTitle(of("re:my \\d+p show"))
                .build();
        var r = parse("my 720p show S01E02.mkv", opts);
        Assertions.assertThat(r.title()).isEqualTo("my 720p show");
    }
    @Test void literalExpectedTitleStillWorks() {
        var opts = options()
                .expectedTitle(of("Show Name"))
                .build();
        var r = parse("Show.Name.S01E02.mkv", opts);
        Assertions.assertThat(r.title()).isEqualTo("Show Name");
    }
}
