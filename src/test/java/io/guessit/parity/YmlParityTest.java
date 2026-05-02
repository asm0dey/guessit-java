package io.guessit.parity;

import io.guessit.Guessit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YmlParityTest {

    // Replicates Python's test_yml.is_same — converts iterable values to sets for comparison,
    // so list ordering is ignored (matching Python guessit YML test semantics).
    // Also normalises scalar comparisons through toString so domain types (Language, Country,
    // etc.) compare equal to their YAML string form.
    private static boolean isSame(Object value, Object expected) {
        if (value == null) return expected == null;
        if (expected == null) return false;
        var vSet = toComparableSet(value);
        var eSet = toComparableSet(expected);
        return vSet.equals(eSet);
    }

    private static Set<String> toComparableSet(Object o) {
        var set = new java.util.LinkedHashSet<String>();
        if (o instanceof Iterable<?> iter) {
            for (var e : iter) set.add(scalarKey(e));
        } else {
            set.add(scalarKey(o));
        }
        return set;
    }

    private static String scalarKey(Object o) {
        return o == null ? "null" : o.toString();
    }

    /** Properties shipped through Phases 1 + 2. Only cases whose expected output is entirely
     *  within this set are tested. */
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
        "website", "streaming_service"
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
        boolean matches = c.expected().entrySet().stream()
            .filter(e -> e.getValue() != null)
            .allMatch(e -> isSame(e.getValue(), result.get(e.getKey())));
        if (c.negative()) {
            assertFalse(matches, c + " negative case unexpectedly matched: expected " + c.expected() + " in " + result);
        } else {
            assertTrue(matches, c + " expected " + c.expected() + " ⊆ result " + result);
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
            .filter(c -> !c.expected().isEmpty())
            .filter(c -> c.expected().keySet().stream().allMatch(PHASE_PROPS::contains));
    }
}
