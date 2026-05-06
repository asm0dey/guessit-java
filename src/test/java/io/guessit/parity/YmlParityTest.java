package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.GuessResultFields;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Canonical;
import org.assertj.core.api.Assertions;
import org.assertj.core.presentation.UnicodeRepresentation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class YmlParityTest {
    static {
        Assertions.useRepresentation(UnicodeRepresentation.UNICODE_REPRESENTATION);
    }

    /**
     * Maps each YAML/JSON property key to the matching {@code GuessResult} Java
     * field name(s). Most keys are 1:1 snake → camel; {@code season}/{@code episode}
     * widen to both the scalar and list field, since the parser/expected builder
     * pick one or the other based on cardinality. Keys not present here are
     * "extras-bucket" properties (color_depth, week, absolute_episode, episode_details,
     * disc) and are checked separately via {@link io.guessit.GuessResult#field}.
     */
    private static final Map<String, String[]> KEY_TO_FIELDS = Map.ofEntries(
            entry("title", new String[]{"title"}),
            entry("alternative_title", new String[]{"alternativeTitleList"}),
            entry("year", new String[]{"year"}),
            entry("date", new String[]{"date"}),
            entry("season", new String[]{"season", "seasonList"}),
            entry("episode", new String[]{"episode", "episodeList"}),
            entry("episode_count", new String[]{"episodeCount"}),
            entry("season_count", new String[]{"seasonCount"}),
            entry("ep   isode_title", new String[]{"episodeTitle"}),
            entry("episode_format", new String[]{"episodeFormat"}),
            entry("type", new String[]{"type"}),
            entry("language", new String[]{"language"}),
            entry("subtitle_language", new String[]{"subtitleLanguage"}),
            entry("country", new String[]{"country"}),
            entry("source", new String[]{"source", "sourceList"}),
            entry("other", new String[]{"other"}),
            entry("video_codec", new String[]{"videoCodec"}),
            entry("audio_codec", new String[]{"audioCodec"}),
            entry("audio_channels", new String[]{"audioChannels"}),
            entry("audio_profile", new String[]{"audioProfile"}),
            entry("video_profile", new String[]{"videoProfile"}),
            entry("video_api", new String[]{"videoApi"}),
            entry("screen_size", new String[]{"screenSize"}),
            entry("aspect_ratio", new String[]{"aspectRatio"}),
            entry("frame_rate", new String[]{"frameRate"}),
            entry("bit_rate", new String[]{"bitRate"}),
            entry("audio_bit_rate", new String[]{"audioBitRate"}),
            entry("video_bit_rate", new String[]{"videoBitRate"}),
            entry("size", new String[]{"size"}),
            entry("container", new String[]{"container"}),
            entry("mimetype", new String[]{"mimetype"}),
            entry("release_group", new String[]{"releaseGroup"}),
            entry("streaming_service", new String[]{"streamingService"}),
            entry("website", new String[]{"website"}),
            entry("edition", new String[]{"edition"}),
            entry("cd", new String[]{"cd"}),
            entry("cd_count", new String[]{"cdCount"}),
            entry("part", new String[]{"part", "partList"}),
            entry("version", new String[]{"version"}),
            entry("film", new String[]{"film"}),
            entry("film_title", new String[]{"filmTitle"}),
            entry("bonus", new String[]{"bonus"}),
            entry("bonus_title", new String[]{"bonusTitle"}),
            entry("crc32", new String[]{"crc32"}),
            entry("proper_count", new String[]{"properCount"})
    );

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        // Build expected GuessResult (typed: "fr" → Language, list scalars → List).
        var actual = Guessit.parse(c.input(), c.options());

        // Separate positive keys from per-key negative keys (Python semantics: a key
        // starting with "-" in the expected map means "this field must NOT have this value").
        var positiveExpected = new LinkedHashMap<String, Object>();
        var negativeExpected = new LinkedHashMap<String, Object>(); // stripped key → value
        for (var e : c.expected().entrySet()) {
            String k = e.getKey();
            // python check_expected: skip when expected_value is None — treat as "any value (or absent)"
            if (e.getValue() == null) continue;
            if (k.startsWith("-")) {
                negativeExpected.put(k.substring(1), e.getValue());
            } else {
                positiveExpected.put(k, e.getValue());
            }
        }

        var expected = YmlExpected.build(positiveExpected);
        var expectedKeys = positiveExpected.keySet();

        if (c.negative()) {
            // Input-level negation: the whole test case is negated; the expected properties
            // must NOT all match simultaneously.
            boolean allMatch = true;
            for (String k : expectedKeys) {
                if (!Canonical.equivalent(GuessResultFields.field(actual, k), GuessResultFields.field(expected, k))) {
                    allMatch = false;
                    break;
                }
            }
            assertThat(allMatch)
                    .as(c + " negative case unexpectedly matched: expected " + expected + " in " + actual)
                    .isFalse();
            return;
        }

        // Split positive keys into typed (have a GuessResult field) vs extras-bucket.
        var typedFields = new ArrayList<String>();
        var extrasKeys = new ArrayList<String>();
        for (String k : expectedKeys) {
            String[] javaFields = KEY_TO_FIELDS.get(k);
            if (javaFields == null) extrasKeys.add(k);
            else Collections.addAll(typedFields, javaFields);
        }

        if (!typedFields.isEmpty()) {
            assertThat(actual)
                    .as(c.toString())
                    .usingRecursiveComparison()
                    .comparingOnlyFields(typedFields.toArray(new String[0]))
                    .withEqualsForType(Canonical::equivalent, Language.class)
                    .withEqualsForType(Canonical::equivalent, Country.class)
                    .ignoringCollectionOrder()
                    .isEqualTo(expected);
        }

        // Extras-bucket keys (color_depth, week, absolute_episode, episode_details, disc):
        // recursive comparison can't drill into the extras Map by name, so per-key check.
        for (String k : extrasKeys) {
            Object actualValue = GuessResultFields.field(actual, k);
            Object expectedValue = GuessResultFields.field(expected, k);
            assertThat(Canonical.keySet(actualValue))
                    .as(c + " field=" + k + " expected " + expectedValue + " in result " + actualValue)
                    .isEqualTo(Canonical.keySet(expectedValue));
        }

        // Per-key negative assertions: fields that must NOT have the specified value.
        for (var e : negativeExpected.entrySet()) {
            String k = e.getKey();
            var negExpected = YmlExpected.build(Map.of(k, e.getValue()));
            Object actualValue = GuessResultFields.field(actual, k);
            Object negExpectedValue = GuessResultFields.field(negExpected, k);
            assertThat(Canonical.equivalent(actualValue, negExpectedValue))
                    .as(c + " field=-" + k + " expected " + negExpectedValue + " in result " + actualValue)
                    .isFalse();
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
                .stream()
                .filter(c -> !c.expected().isEmpty());
    }
}
