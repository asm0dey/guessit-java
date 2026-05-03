package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.util.Canonical;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class YmlParityTest {

    // Comparison delegates to io.guessit.util.Canonical so the library owns the
    // YAML-scalar / typed-value equivalence rules (Python babelfish parity).
    private static void assertSame(Object value, Object expected) {
        assertThat(Canonical.keySet(value)).isEqualTo(Canonical.keySet(expected));
    }

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
        var result = Guessit.parse(c.input(), c.options()).toMap();
        // Python guessit YML semantics: expected entries must each be present in result
        // (subset match), and result may contain additional inferred fields.
        // Skip null expected values (Python's check_expected skips when expected_value is None).
        // Uses isSame for set-based comparison (ordering-insensitive for lists), matching
        // Python's test_yml.is_same which converts to set().
        var expectedEntries = c.expected().entrySet().stream()
                .filter(e -> e.getValue() != null)
                .toList();
        if (c.negative()) {
            // Python YML semantics: negative case passes if the *full* expected mapping
            // doesn't appear in the result. At least one entry must mismatch.
            boolean allMatch = expectedEntries.stream()
                .allMatch(e -> Canonical.equivalent(result.get(e.getKey()), e.getValue()));
            assertThat(allMatch)
                    .as(c + " negative case unexpectedly matched: expected " + c.expected() + " in " + result)
                    .isFalse();
        } else {
            assertThat(expectedEntries)
                    .as(c + " expected " + c.expected() + " ⊆ result " + result)
                    .allSatisfy(e -> assertSame(result.get(e.getKey()), e.getValue()));
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
                .filter(c -> !c.expected().isEmpty())
                .filter(c -> PHASE_PROPS.containsAll(c.expected().keySet()));
    }
}
