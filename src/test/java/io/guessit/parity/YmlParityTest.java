package io.guessit.parity;

import io.guessit.Guessit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YmlParityTest {

    /** Properties shipped through Phase 1. Only cases whose expected output is entirely
     *  within this set are tested. */
    private static final Set<String> PHASE_1_PROPS = Set.of(
        "year", "container", "screen_size", "aspect_ratio", "frame_rate",
        "video_codec", "video_profile", "color_depth", "video_api",
        "audio_codec", "audio_profile", "audio_channels"
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        var result = Guessit.parse(c.input(), c.options()).toMap();
        // Python guessit YML semantics: expected entries must each be present in result
        // (subset match), and result may contain additional inferred fields.
        boolean matches = c.expected().entrySet().stream()
            .allMatch(e -> java.util.Objects.equals(e.getValue(), result.get(e.getKey())));
        if (c.negative()) {
            assertFalse(matches, "negative case unexpectedly matched: expected " + c.expected() + " in " + result);
        } else {
            assertTrue(matches, "expected " + c.expected() + " ⊆ result " + result);
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
            .filter(c -> !c.expected().isEmpty())
            .filter(c -> c.expected().keySet().stream().allMatch(PHASE_1_PROPS::contains));
    }
}
