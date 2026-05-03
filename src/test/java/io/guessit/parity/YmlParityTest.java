package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.util.Canonical;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class YmlParityTest {

    /**
     * Properties shipped through Phases 1 + 2 + 3 + 4. Only cases whose expected output is entirely
     * within this set are tested.
     */
    private static final Set<String> PHASE_PROPS = Set.of(
            // Phase 1
            "year", "container", "screen_size", "aspect_ratio", "frame_rate",
            "video_codec", "video_profile", "color_depth", "video_api",
            "audio_codec", "audio_profile", "audio_channels",
            // Phase 2
            "source", "other",
            "language", "subtitle_language",
            "country",
            "release_group",
            "website", "streaming_service",
            // Phase 3
            "date", "week", "absolute_episode", "disc",
            "episode_details", "episode_format", "version",
            "season", "episode", "season_count", "episode_count",
            // Phase 4
            "title", "alternative_title", "episode_title", "type"
    );

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        // Functional parity: build the expected GuessResult (partial, typed: "fr" → Language,
        // "1995" → Integer, list scalars → List), then check each expected key against the
        // matching field on the actual GuessResult. Subset semantics: extra inferred fields
        // on actual are allowed; only declared expected keys must match.
        var actual = Guessit.parse(c.input(), c.options());
        var expected = YmlExpected.build(c.expected());
        var expectedKeys = c.expected().entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        if (c.negative()) {
            boolean allMatch = true;
            for (String k : expectedKeys) {
                if (!Canonical.equivalent(actual.field(k), expected.field(k))) {
                    allMatch = false;
                    break;
                }
            }
            assertThat(allMatch)
                    .as(c + " negative case unexpectedly matched: expected " + expected + " in " + actual)
                    .isFalse();
        } else {
            for (String k : expectedKeys) {
                Object actualValue = actual.field(k);
                Object expectedValue = expected.field(k);
                assertThat(Canonical.keySet(actualValue))
                        .as(c + " field=" + k + " expected " + expectedValue + " in result " + actualValue)
                        .isEqualTo(Canonical.keySet(expectedValue));
            }
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
                .filter(c -> !c.expected().isEmpty())
                .filter(c -> PHASE_PROPS.containsAll(c.expected().keySet()));
    }
}
