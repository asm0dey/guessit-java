package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Canonical;
import org.assertj.core.api.Assertions;
import org.assertj.core.presentation.UnicodeRepresentation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

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
            Map.entry("title", new String[]{"title"}),
            Map.entry("alternative_title", new String[]{"alternativeTitleList"}),
            Map.entry("year", new String[]{"year"}),
            Map.entry("date", new String[]{"date"}),
            Map.entry("season", new String[]{"season", "seasonList"}),
            Map.entry("episode", new String[]{"episode", "episodeList"}),
            Map.entry("episode_count", new String[]{"episodeCount"}),
            Map.entry("season_count", new String[]{"seasonCount"}),
            Map.entry("episode_title", new String[]{"episodeTitle"}),
            Map.entry("episode_format", new String[]{"episodeFormat"}),
            Map.entry("type", new String[]{"type"}),
            Map.entry("language", new String[]{"language"}),
            Map.entry("subtitle_language", new String[]{"subtitleLanguage"}),
            Map.entry("country", new String[]{"country"}),
            Map.entry("source", new String[]{"source"}),
            Map.entry("other", new String[]{"other"}),
            Map.entry("video_codec", new String[]{"videoCodec"}),
            Map.entry("audio_codec", new String[]{"audioCodec"}),
            Map.entry("audio_channels", new String[]{"audioChannels"}),
            Map.entry("audio_profile", new String[]{"audioProfile"}),
            Map.entry("video_profile", new String[]{"videoProfile"}),
            Map.entry("video_api", new String[]{"videoApi"}),
            Map.entry("screen_size", new String[]{"screenSize"}),
            Map.entry("aspect_ratio", new String[]{"aspectRatio"}),
            Map.entry("frame_rate", new String[]{"frameRate"}),
            Map.entry("bit_rate", new String[]{"bitRate"}),
            Map.entry("audio_bit_rate", new String[]{"audioBitRate"}),
            Map.entry("video_bit_rate", new String[]{"videoBitRate"}),
            Map.entry("size", new String[]{"size"}),
            Map.entry("container", new String[]{"container"}),
            Map.entry("mimetype", new String[]{"mimetype"}),
            Map.entry("release_group", new String[]{"releaseGroup"}),
            Map.entry("streaming_service", new String[]{"streamingService"}),
            Map.entry("website", new String[]{"website"}),
            Map.entry("edition", new String[]{"edition"}),
            Map.entry("cd", new String[]{"cd"}),
            Map.entry("cd_count", new String[]{"cdCount"}),
            Map.entry("part", new String[]{"part"}),
            Map.entry("version", new String[]{"version"}),
            Map.entry("film", new String[]{"film"}),
            Map.entry("film_title", new String[]{"filmTitle"}),
            Map.entry("bonus", new String[]{"bonus"}),
            Map.entry("bonus_title", new String[]{"bonusTitle"}),
            Map.entry("crc32", new String[]{"crc32"})
    );

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        // Build expected GuessResult (typed: "fr" → Language, list scalars → List).
        var actual = Guessit.parse(c.input(), c.options());

        // Separate positive keys from per-key negative keys (Python semantics: a key
        // starting with "-" in the expected map means "this field must NOT have this value").
        var positiveExpected = new java.util.LinkedHashMap<String, Object>();
        var negativeExpected = new java.util.LinkedHashMap<String, Object>(); // stripped key → value
        for (var e : c.expected().entrySet()) {
            String k = e.getKey();
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
                if (!Canonical.equivalent(actual.field(k), expected.field(k))) {
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
            Object actualValue = actual.field(k);
            Object expectedValue = expected.field(k);
            assertThat(Canonical.keySet(actualValue))
                    .as(c + " field=" + k + " expected " + expectedValue + " in result " + actualValue)
                    .isEqualTo(Canonical.keySet(expectedValue));
        }

        // Per-key negative assertions: fields that must NOT have the specified value.
        for (var e : negativeExpected.entrySet()) {
            String k = e.getKey();
            var negExpected = YmlExpected.build(Map.of(k, e.getValue()));
            Object actualValue = actual.field(k);
            Object negExpectedValue = negExpected.field(k);
            assertThat(Canonical.equivalent(actualValue, negExpectedValue))
                    .as(c + " field=-" + k + " expected " + negExpectedValue + " in result " + actualValue)
                    .isFalse();
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
                .filter(c -> !c.expected().isEmpty());
    }
}
