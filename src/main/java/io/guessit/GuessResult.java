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
    @Opt String alternativeTitle,
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
    @Opt String screenSize, @Opt String aspectRatio, @Opt Integer frameRate,
    @Opt Quantity bitRate, @Opt Quantity size,
    @Opt String container, @Opt String mimetype,
    @Opt String releaseGroup, @Opt String streamingService, @Opt String website,
    @Opt String edition, @Opt Integer cd, @Opt Integer cdCount,
    @Opt Integer part, @Opt Integer version, @Opt Integer film, @Opt String filmTitle,
    @Opt Integer bonus, @Opt String bonusTitle, @Opt String crc32,
    @Opt Map<String, Object> extras
) {
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        putIfNotNull(m, "title", title);
        putIfNotNull(m, "alternative_title", alternativeTitle);
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
        if (size != null) m.put("size", size.format());
        putIfNotNull(m, "container", container);
        putIfNotNull(m, "mimetype", mimetype);
        putIfNotNull(m, "release_group", releaseGroup);
        putIfNotNull(m, "streaming_service", streamingService);
        putIfNotNull(m, "website", website);
        putIfNotNull(m, "edition", edition);
        putIfNotNull(m, "cd", cd);
        putIfNotNull(m, "cd_count", cdCount);
        putIfNotNull(m, "part", part);
        putIfNotNull(m, "version", version);
        putIfNotNull(m, "film", film);
        putIfNotNull(m, "film_title", filmTitle);
        putIfNotNull(m, "bonus", bonus);
        putIfNotNull(m, "bonus_title", bonusTitle);
        putIfNotNull(m, "crc32", crc32);
        if (extras != null) extras.forEach(m::putIfAbsent);
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static void putSeasonOrEpisode(Map<String, Object> m, String k, Integer single, List<Integer> list) {
        if (list != null && !list.isEmpty()) m.put(k, list.size() == 1 ? list.get(0) : list);
        else if (single != null) m.put(k, single);
    }

    private static void putList(Map<String, Object> m, String k, List<?> list) {
        if (list == null || list.isEmpty()) return;
        m.put(k, list.size() == 1 ? list.get(0) : list);
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

    /** Convenience alias so callers can write `GuessResult.builder().title(...)...build()`. */
    public static GuessResultBuilder builder() { return GuessResultBuilder.result(); }
}
