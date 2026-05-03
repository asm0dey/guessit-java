package io.guessit.parity;

import io.guessit.Guessit;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Canonical;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
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
            Map.entry("alternative_title", new String[]{"alternativeTitle"}),
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
            Map.entry("size", new String[]{"size"}),
            Map.entry("container", new String[]{"container"}),
            Map.entry("mimetype", new String[]{"mimetype"}),
            Map.entry("release_group", new String[]{"releaseGroup"}),
            Map.entry("streaming_service", new String[]{"streamingService"}),
            Map.entry("website", new String[]{"website"}),
            Map.entry("edition", new String[]{"edition"})
    );

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        // Build expected GuessResult (typed: "fr" → Language, list scalars → List).
        var actual = Guessit.parse(c.input(), c.options());
        var expected = YmlExpected.build(c.expected());
        var expectedKeys = c.expected().keySet();

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
            return;
        }

        // Split keys into typed (have a GuessResult field) vs extras-bucket.
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
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/")
                .filter(c -> !c.expected().isEmpty())
                .filter(c -> PHASE_PROPS.containsAll(c.expected().keySet()));
    }
}
