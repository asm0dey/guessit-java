package io.guessit.parity;

import io.guessit.GuessResult;
import io.guessit.GuessResultBuilder;
import io.guessit.lang.Country;
import io.guessit.lang.Language;
import io.guessit.lang.LanguageRegistry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Coerces a parity-test YAML expected map into a typed {@link GuessResult} so the parity
 * comparison stays record-vs-record. Each key resolves to the matching GuessResult field
 * with the right Java type (String stays String, "fr" → {@link Language}, lists stay lists, …).
 */
final class YmlExpected {
    private YmlExpected() {}

    static GuessResult build(Map<String, Object> expected) {
        var b = GuessResult.builder();
        var extras = new java.util.LinkedHashMap<String, Object>();
        for (var e : expected.entrySet()) {
            if (e.getValue() == null) continue;
            if (!apply(b, e.getKey(), e.getValue())) extras.put(e.getKey(), e.getValue());
        }
        if (!extras.isEmpty()) b.extras(extras);
        return b.build();
    }

    private static boolean apply(GuessResultBuilder b, String key, Object v) {
        switch (key) {
            case "title" -> b.title(asString(v));
            case "alternative_title" -> b.alternativeTitleList(asStrings(v));
            case "year" -> b.year(asInt(v));
            case "date" -> b.date(asDate(v));
            case "season" -> setSeason(b, v);
            case "episode" -> setEpisode(b, v);
            case "episode_count" -> b.episodeCount(asInt(v));
            case "season_count" -> b.seasonCount(asInt(v));
            case "episode_title" -> b.episodeTitle(asString(v));
            case "episode_format" -> b.episodeFormat(asString(v));
            case "type" -> b.type(asString(v));
            case "language" -> b.language(asLanguages(v));
            case "subtitle_language" -> b.subtitleLanguage(asLanguages(v));
            case "country" -> b.country(asCountries(v));
            case "source" -> b.source(asString(v));
            case "other" -> b.other(asStrings(v));
            case "video_codec" -> b.videoCodec(asStrings(v));
            case "audio_codec" -> b.audioCodec(asStrings(v));
            case "audio_channels" -> b.audioChannels(asStrings(v));
            case "audio_profile" -> b.audioProfile(asStrings(v));
            case "video_profile" -> b.videoProfile(asStrings(v));
            case "video_api" -> b.videoApi(asStrings(v));
            case "screen_size" -> b.screenSize(asString(v));
            case "aspect_ratio" -> b.aspectRatio(asDouble(v));
            case "frame_rate" -> b.frameRate(asString(v));
            case "container" -> b.container(asString(v));
            case "mimetype" -> b.mimetype(asString(v));
            case "release_group" -> b.releaseGroup(asString(v));
            case "streaming_service" -> b.streamingService(asString(v));
            case "website" -> b.website(asString(v));
            case "edition" -> b.edition(asStrings(v));
            case "cd" -> b.cd(asInt(v));
            case "cd_count" -> b.cdCount(asInt(v));
            case "part" -> b.part(asInt(v));
            case "version" -> b.version(asInt(v));
            case "film" -> b.film(asInt(v));
            case "film_title" -> b.filmTitle(asString(v));
            case "bonus" -> b.bonus(asInt(v));
            case "bonus_title" -> b.bonusTitle(asString(v));
            case "crc32" -> b.crc32(asString(v));
            default -> { return false; }
        }
        return true;
    }

    private static void setSeason(GuessResultBuilder b, Object v) {
        var ints = asInts(v);
        if (ints.size() == 1) b.season(ints.getFirst());
        else b.seasonList(ints);
    }

    private static void setEpisode(GuessResultBuilder b, Object v) {
        var ints = asInts(v);
        if (ints.size() == 1) b.episode(ints.getFirst());
        else b.episodeList(ints);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s.trim());
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) return Double.parseDouble(s.trim());
        return null;
    }

    private static LocalDate asDate(Object v) {
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof Date d) return d.toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
        if (v instanceof String s) return LocalDate.parse(s);
        return null;
    }

    private static List<Integer> asInts(Object v) {
        var out = new ArrayList<Integer>();
        for (var e : asIterable(v)) out.add(asInt(e));
        return out;
    }

    private static List<String> asStrings(Object v) {
        var out = new ArrayList<String>();
        for (var e : asIterable(v)) out.add(asString(e));
        return out;
    }

    private static List<Language> asLanguages(Object v) {
        var reg = LanguageRegistry.instance();
        var out = new ArrayList<Language>();
        for (var e : asIterable(v)) {
            if (e instanceof Language l) { out.add(l); continue; }
            var lang = reg.find(e.toString()).orElse(null);
            if (lang != null) out.add(lang);
        }
        return out;
    }

    private static List<Country> asCountries(Object v) {
        var reg = LanguageRegistry.instance();
        var out = new ArrayList<Country>();
        for (var e : asIterable(v)) {
            if (e instanceof Country c) { out.add(c); continue; }
            var c = reg.findCountry(e.toString()).orElse(null);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static Iterable<?> asIterable(Object v) {
        if (v instanceof Iterable<?> it) return it;
        return List.of(v);
    }
}
