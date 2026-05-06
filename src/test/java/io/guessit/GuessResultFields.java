package io.guessit;

import java.util.List;
import java.util.Map;

/**
 * Functional accessor for parity comparisons: returns the typed field value bound to a
 * snake_case property name (matching YAML expected keys), without the {@link GuessResult#toMap()}
 * round-trip. Multi-value lists stay as {@link List}; season/episode collapse to a single
 * value when only one is present (mirroring Python guessit's scalar-when-single contract).
 * Returns {@code null} when no value is set or the key is unknown.
 */
public final class GuessResultFields {
    private GuessResultFields() {}

    public static Object field(GuessResult r, String key) {
        return switch (key) {
            case "title" -> r.title();
            case "alternative_title" -> singleOrList(r.alternativeTitleList());
            case "year" -> r.year();
            case "date" -> r.date();
            case "season" -> r.seasonList() != null && !r.seasonList().isEmpty()
                ? (r.seasonList().size() == 1 ? r.seasonList().getFirst() : r.seasonList()) : r.season();
            case "episode" -> r.episodeList() != null && !r.episodeList().isEmpty()
                ? (r.episodeList().size() == 1 ? r.episodeList().getFirst() : r.episodeList()) : r.episode();
            case "episode_count" -> r.episodeCount();
            case "season_count" -> r.seasonCount();
            case "episode_title" -> r.episodeTitle();
            case "episode_format" -> r.episodeFormat();
            case "type" -> r.type();
            case "language" -> singleOrList(r.language());
            case "subtitle_language" -> singleOrList(r.subtitleLanguage());
            case "country" -> singleOrList(r.country());
            case "source" -> r.sourceList() != null && !r.sourceList().isEmpty()
                ? (r.sourceList().size() == 1 ? r.sourceList().getFirst() : r.sourceList()) : r.source();
            case "other" -> singleOrList(r.other());
            case "video_codec" -> singleOrList(r.videoCodec());
            case "audio_codec" -> singleOrList(r.audioCodec());
            case "audio_channels" -> singleOrList(r.audioChannels());
            case "audio_profile" -> singleOrList(r.audioProfile());
            case "video_profile" -> singleOrList(r.videoProfile());
            case "video_api" -> singleOrList(r.videoApi());
            case "screen_size" -> r.screenSize();
            case "aspect_ratio" -> r.aspectRatio();
            case "frame_rate" -> r.frameRate();
            case "bit_rate" -> r.bitRate() == null ? null : r.bitRate().format();
            case "audio_bit_rate" -> r.audioBitRate() == null ? null : r.audioBitRate().format();
            case "video_bit_rate" -> r.videoBitRate() == null ? null : r.videoBitRate().format();
            case "size" -> r.size() == null ? null : r.size().format();
            case "container" -> r.container();
            case "mimetype" -> r.mimetype();
            case "release_group" -> r.releaseGroup();
            case "streaming_service" -> r.streamingService();
            case "website" -> r.website();
            case "edition" -> singleOrList(r.edition());
            case "cd" -> r.cd();
            case "cd_count" -> r.cdCount();
            case "part" -> r.partList() != null && !r.partList().isEmpty()
                ? (r.partList().size() == 1 ? r.partList().getFirst() : r.partList()) : r.part();
            case "version" -> r.version();
            case "film" -> r.film();
            case "film_title" -> r.filmTitle();
            case "bonus" -> r.bonus();
            case "bonus_title" -> r.bonusTitle();
            case "crc32" -> r.crc32();
            case "proper_count" -> r.properCount();
            default -> {
                Map<String, Object> extras = r.extras();
                yield extras == null ? null : extras.get(key);
            }
        };
    }

    private static Object singleOrList(List<?> list) {
        if (list == null || list.isEmpty()) return null;
        return list.size() == 1 ? list.getFirst() : list;
    }
}
