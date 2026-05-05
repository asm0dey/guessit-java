package io.guessit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.util.Quantity;
import org.jilt.Builder;
import org.jilt.BuilderStyle;
import org.jilt.Opt;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Builder(style = BuilderStyle.CLASSIC, factoryMethod = "result")
public record GuessResult(
    @Opt String title,
    @Opt List<String> alternativeTitleList,
    @Opt Integer year,
    @Opt LocalDate date,
    @Opt Integer season, @Opt List<Integer> seasonList,
    @Opt Integer episode, @Opt List<Integer> episodeList,
    @Opt Integer episodeCount, @Opt Integer seasonCount,
    @Opt String episodeTitle,
    @Opt String episodeFormat,
    @Opt String type,
    @Opt List<Language> language, @Opt List<Language> subtitleLanguage,
    @Opt List<Country> country,
    @Opt String source, @Opt List<String> other,
    @Opt List<String> videoCodec, @Opt List<String> audioCodec,
    @Opt List<String> audioChannels, @Opt List<String> audioProfile,
    @Opt List<String> videoProfile, @Opt List<String> videoApi,
    @Opt String screenSize, @Opt Double aspectRatio, @Opt String frameRate,
    @Opt Quantity bitRate, @Opt Quantity audioBitRate, @Opt Quantity videoBitRate, @Opt Quantity size,
    @Opt String container, @Opt String mimetype,
    @Opt String releaseGroup, @Opt String streamingService, @Opt String website,
    @Opt List<String> edition, @Opt Integer cd, @Opt Integer cdCount,
    @Opt Integer part, @Opt Integer version, @Opt Integer film, @Opt String filmTitle,
    @Opt Integer bonus, @Opt String bonusTitle, @Opt String crc32,
    @Opt Integer properCount,
    @Opt Map<String, Object> extras
) {
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        putIfNotNull(m, "title", title);
        putList(m, "alternative_title", alternativeTitleList);
        putIfNotNull(m, "year", year);
        putIfNotNull(m, "date", date);
        putSeasonOrEpisode(m, "season", season, seasonList);
        putSeasonOrEpisode(m, "episode", episode, episodeList);
        putIfNotNull(m, "episode_count", episodeCount);
        putIfNotNull(m, "season_count", seasonCount);
        putIfNotNull(m, "episode_title", episodeTitle);
        putIfNotNull(m, "episode_format", episodeFormat);
        putIfNotNull(m, "type", type);
        putList(m, "language", language);
        putList(m, "subtitle_language", subtitleLanguage);
        putList(m, "country", country);
        putIfNotNull(m, "source", source);
        putList(m, "other", other);
        putList(m, "video_codec", videoCodec);
        putList(m, "audio_codec", audioCodec);
        putList(m, "audio_channels", audioChannels);
        putList(m, "audio_profile", audioProfile);
        putList(m, "video_profile", videoProfile);
        putList(m, "video_api", videoApi);
        putIfNotNull(m, "screen_size", screenSize);
        putIfNotNull(m, "aspect_ratio", aspectRatio);
        putIfNotNull(m, "frame_rate", frameRate);
        if (bitRate != null) m.put("bit_rate", bitRate.format());
        if (audioBitRate != null) m.put("audio_bit_rate", audioBitRate.format());
        if (videoBitRate != null) m.put("video_bit_rate", videoBitRate.format());
        if (size != null) m.put("size", size.format());
        putIfNotNull(m, "container", container);
        putIfNotNull(m, "mimetype", mimetype);
        putIfNotNull(m, "release_group", releaseGroup);
        putIfNotNull(m, "streaming_service", streamingService);
        putIfNotNull(m, "website", website);
        putList(m, "edition", edition);
        putIfNotNull(m, "cd", cd);
        putIfNotNull(m, "cd_count", cdCount);
        putIfNotNull(m, "part", part);
        putIfNotNull(m, "version", version);
        putIfNotNull(m, "film", film);
        putIfNotNull(m, "film_title", filmTitle);
        putIfNotNull(m, "bonus", bonus);
        putIfNotNull(m, "bonus_title", bonusTitle);
        putIfNotNull(m, "crc32", crc32);
        putIfNotNull(m, "proper_count", properCount);
        if (extras != null) extras.forEach(m::putIfAbsent);
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static void putSeasonOrEpisode(Map<String, Object> m, String k, Integer single, List<Integer> list) {
        if (list != null && !list.isEmpty()) m.put(k, list.size() == 1 ? list.getFirst() : list);
        else if (single != null) m.put(k, single);
    }

    private static void putList(Map<String, Object> m, String k, List<?> list) {
        if (list == null || list.isEmpty()) return;
        m.put(k, list.size() == 1 ? list.getFirst() : list);
    }

    /**
     * Functional accessor for parity comparisons: returns the typed field value bound to a
     * snake_case property name (matching YAML expected keys), without the {@link #toMap()}
     * round-trip. Multi-value lists stay as {@link List}; season/episode collapse to a single
     * value when only one is present (mirroring Python guessit's scalar-when-single contract).
     * Returns {@code null} when no value is set or the key is unknown.
     */
    public Object field(String key) {
        return switch (key) {
            case "title" -> title;
            case "alternative_title" -> singleOrList(alternativeTitleList);
            case "year" -> year;
            case "date" -> date;
            case "season" -> seasonList != null && !seasonList.isEmpty()
                ? (seasonList.size() == 1 ? seasonList.getFirst() : seasonList) : season;
            case "episode" -> episodeList != null && !episodeList.isEmpty()
                ? (episodeList.size() == 1 ? episodeList.getFirst() : episodeList) : episode;
            case "episode_count" -> episodeCount;
            case "season_count" -> seasonCount;
            case "episode_title" -> episodeTitle;
            case "episode_format" -> episodeFormat;
            case "type" -> type;
            case "language" -> singleOrList(language);
            case "subtitle_language" -> singleOrList(subtitleLanguage);
            case "country" -> singleOrList(country);
            case "source" -> source;
            case "other" -> singleOrList(other);
            case "video_codec" -> singleOrList(videoCodec);
            case "audio_codec" -> singleOrList(audioCodec);
            case "audio_channels" -> singleOrList(audioChannels);
            case "audio_profile" -> singleOrList(audioProfile);
            case "video_profile" -> singleOrList(videoProfile);
            case "video_api" -> singleOrList(videoApi);
            case "screen_size" -> screenSize;
            case "aspect_ratio" -> aspectRatio;
            case "frame_rate" -> frameRate;
            case "bit_rate" -> bitRate == null ? null : bitRate.format();
            case "audio_bit_rate" -> audioBitRate == null ? null : audioBitRate.format();
            case "video_bit_rate" -> videoBitRate == null ? null : videoBitRate.format();
            case "size" -> size == null ? null : size.format();
            case "container" -> container;
            case "mimetype" -> mimetype;
            case "release_group" -> releaseGroup;
            case "streaming_service" -> streamingService;
            case "website" -> website;
            case "edition" -> singleOrList(edition);
            case "cd" -> cd;
            case "cd_count" -> cdCount;
            case "part" -> part;
            case "version" -> version;
            case "film" -> film;
            case "film_title" -> filmTitle;
            case "bonus" -> bonus;
            case "bonus_title" -> bonusTitle;
            case "crc32" -> crc32;
            case "proper_count" -> properCount;
            default -> extras == null ? null : extras.get(key);
        };
    }

    private static Object singleOrList(List<?> list) {
        if (list == null || list.isEmpty()) return null;
        return list.size() == 1 ? list.getFirst() : list;
    }

    public String toJson() {
        try { return JSON.writeValueAsString(toMap()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public String toYaml() {
        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(opts).dump(toMap());
    }

}
