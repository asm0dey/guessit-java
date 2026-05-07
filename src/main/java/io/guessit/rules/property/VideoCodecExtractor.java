package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Extracts {@code video_codec} (H.264, H.265, MPEG-2, …) and the related
 * {@code video_profile}, {@code video_api}, and {@code color_depth} properties.
 *
 * <p>Codec aliases are listed as a small table of {regex, canonical name}
 * pairs. {@link Abbreviations#dash} expands the literal {@code -} in each
 * source so {@code "x264"}, {@code "x.264"}, and {@code "x-264"} all match
 * the same pattern.
 *
 * <p>The {@code hevc10} compound is special-cased: it must produce
 * <em>both</em> a {@code video_codec=H.265} match and a
 * {@code color_depth=10-bit} match on adjacent sub-spans, otherwise the
 * conflict solver would drop one for overlapping the other.
 */
public final class VideoCodecExtractor implements Extractor {

    public static final String VIDEO_PROFILE = "video_profile";
    public static final String VIDEO_CODEC = "video_codec";
    private static final String VIDEO_PROFILE_RULE_TAG = "video_profile.rule";
    private static final MatchName VIDEO_CODEC_NAME = MatchName.VIDEO_CODEC;
    private static final MatchName VIDEO_PROFILE_NAME = MatchName.VIDEO_PROFILE;
    private static final MatchName COLOR_DEPTH_NAME = MatchName.COLOR_DEPTH;
    private static final MatchName VIDEO_API_NAME = MatchName.VIDEO_API;

    private static final Pattern HEVC10 = Pattern.compile("(hevc)(10)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVC_HD = Pattern.compile(Abbreviations.dash("AVC(?:HD)?"), Pattern.CASE_INSENSITIVE);

    private final ConcurrentMap<String, Pattern> dashCache = new ConcurrentHashMap<>();

    private Pattern compileDashed(String src) {
        return dashCache.computeIfAbsent(src, s -> Pattern.compile(Abbreviations.dash(s), Pattern.CASE_INSENSITIVE));
    }

    @Override public String name() { return VIDEO_CODEC; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        // Match with sep on either side so cases like "PDTVx264-JIVE" pick up
        // x264 (sepsAfter only) — mirrors python's or_(seps_before, seps_after).
        var v = Validators.sepsBefore(input).or(Validators.sepsAfter(input));

        // codec
        addCodec(ctx, "Rv\\d{2}",                                   "RealVideo", v);
        addCodec(ctx, "Mpe?g-?2",                                    "MPEG-2",    v);
        addCodec(ctx, "[hx]-?262",                                   "MPEG-2",    v);
        addCodec(ctx, "DVDivX|DivX",                                 "DivX",      v);
        addCodec(ctx, "XviD",                                        "Xvid",      v);
        addCodec(ctx, "VC-?1",                                       "VC-1",      v);
        addCodec(ctx, "VP7",                                         "VP7",       v);
        addCodec(ctx, "VP80|VP8",                                    "VP8",       v);
        addCodec(ctx, "VP9",                                         "VP9",       v);
        addCodec(ctx, "[hx]-?263",                                   "H.263",     v);
        addCodec(ctx, "[hx]-?264|(?:MPEG-?4)?AVC(?:HD)?",            "H.264",     v);
        addCodec(ctx, "[hx]-?265|HEVC",                              "H.265",     v);

        // hevc(10) → H.265 + 10-bit color_depth (sub-span matches to avoid ConflictSolver overlap)
        // Validated with sepsBefore only (right side is '10', not a separator)
        var m = HEVC10.matcher(input);
        while (m.find()) {
            int g1s = m.start(1);
            int g1e = m.end(1);
            int g2s = m.start(2);
            int g2e = m.end(2);
            String raw1 = m.group(1);
            String raw2 = m.group(2);
            if (!Validators.sepsBefore(input).test(
                    new Match(MatchName.DUMMY, "", g1s, g1e, raw1, 0, Set.of(), false)))
                continue;
            // Remove any shorter video_codec match at same start that overlaps (e.g., HEVC within HEVC10)
            ctx.matches.named(VIDEO_CODEC_NAME)
                .filter(e -> e.start() == g1s && e.end() < g1e)
                .toList()
                .forEach(ctx.matches::remove);
            ctx.matches.add(new Match(VIDEO_CODEC_NAME, "H.265", g1s, g1e, raw1, 1000,
                Set.of("source-suffix", "streaming_service.suffix"), false));
            // Tag color_depth suffix as video-codec-suffix so the abutting codec
            // match passes validateVideoCodec without sepsAfter.
            ctx.matches.add(new Match(COLOR_DEPTH_NAME, "10-bit", g2s, g2e, raw2, 1000,
                Set.of("video-codec-suffix", "derivedFrom:video_codec"), false));
        }

        // video_profile (validated, validators enforce seps_surround)
        // Tagged VIDEO_PROFILE_RULE_TAG means: drop in postProcess if no video_codec exists anywhere.
        addStrTagged(ctx, "Baseline",                      Set.of("BP"),  v);
        addStrTagged(ctx, "Extended",                      Set.of("XP","EP"), v);
        addStrTagged(ctx, "Main",                          Set.of("MP"),  v);
        addStrTagged(ctx, "High",                          Set.of("HP","HiP"), v);
        addStrTagged(ctx, "Scalable Video Coding",         Set.of("SC","SVC"), v);
        addRegexProfileTagged(ctx, v);
        addStrTagged(ctx, "High Efficiency Video Coding",  Set.of("HEVC"), v);
        addRegexProfile(ctx, "Hi422P",                                      "High 4:2:2", v);
        addRegexProfile(ctx, "Hi444PP",                                     "High 4:4:4 Predictive", v);
        addRegexProfile(ctx, "Hi10P?",                                      "High 10", v);

        // video_api
        addStr(ctx, Set.of("DXVA"), v);

        // color_depth
        addRegexNamed(ctx, COLOR_DEPTH_NAME, "12.?bits?", "12-bit", v);
        addRegexNamed(ctx, COLOR_DEPTH_NAME, "10.?bits?|YUV420P10|Hi10P?", "10-bit", v);
        addRegexNamed(ctx, COLOR_DEPTH_NAME, "8.?bits?",  "8-bit",  v);
    }

    /** Replicates ValidateVideoCodec + VideoProfileRule. */
    @Override
    public void postProcess(ParseContext ctx) {
        validateVideoCodec(ctx);
        boolean hasCodec = ctx.matches.named(VIDEO_CODEC_NAME).findAny().isPresent();
        if (hasCodec) return;
        ctx.matches.named(VIDEO_PROFILE_NAME)
            .filter(p -> p.tags().contains(VIDEO_PROFILE_RULE_TAG))
            .toList()
            .forEach(ctx.matches::remove);
    }

    private void validateVideoCodec(ParseContext ctx) {
        var input = ctx.input;
        var sepsBefore = Validators.sepsBefore(input);
        var sepsAfter = Validators.sepsAfter(input);
        var allMatches = ctx.matches.all().toList();
        var toRemove = new java.util.ArrayList<Match>();
        for (var codec : ctx.matches.named(VIDEO_CODEC_NAME).toList()) {
            boolean before = sepsBefore.test(codec)
                || allMatches.stream().anyMatch(m -> m.end() == codec.start()
                    && m.tags().contains("video-codec-prefix"));
            boolean after = sepsAfter.test(codec)
                || allMatches.stream().anyMatch(m -> m.start() == codec.end()
                    && m.tags().contains("video-codec-suffix"));
            if (!before || !after) toRemove.add(codec);
        }
        toRemove.forEach(ctx.matches::remove);
    }

    private void addCodec(ParseContext ctx, String src, String value, java.util.function.Predicate<Match> v) {
        var p = compileDashed(src);
        var opts = RegexOpts.defaults().withValidator(v).withValue(_ -> value);
        // Tag video_codec matches with source-suffix so adjacent source matches
        // (e.g. PDTV in "PDTVx264") pass ValidateSourcePrefixSuffix when the
        // codec abuts the source without a separator.
        for (var m : PatternMatcher.regex(ctx.input, p, VIDEO_CODEC_NAME, opts)) {
            ctx.matches.add(new Match(VIDEO_CODEC_NAME, m.value(), m.start(), m.end(), m.raw(),
                m.priority(), Set.of("source-suffix", "streaming_service.suffix"), false));
        }
    }
    private void addRegexProfile(ParseContext ctx, String src, String value, java.util.function.Predicate<Match> v) {
        addRegexNamed(ctx, MatchName.VIDEO_PROFILE, src, value, v);
    }
    private void addRegexNamed(ParseContext ctx, MatchName name, String src, String value, java.util.function.Predicate<Match> v) {
        var p = compileDashed(src);
        var opts = RegexOpts.defaults().withValidator(v).withValue(_ -> value);
        for (var m : PatternMatcher.regex(ctx.input, p, name, opts)) ctx.matches.add(m);
    }
    private void addStr(ParseContext ctx, Set<String> needles,
                        Predicate<Match> v) {
        var opts = StringOpts.defaults().withValidator(v);
        for (var m : PatternMatcher.string(ctx.input, needles, VIDEO_API_NAME, opts)) {
            ctx.matches.add(new Match(VIDEO_API_NAME, "DXVA", m.start(), m.end(), m.raw(),
                m.priority(), m.tags(), m.isPrivate()));
        }
    }
    private void addStrTagged(ParseContext ctx, String value, Set<String> needles,
                              Predicate<Match> v) {
        var opts = StringOpts.defaults().withValidator(v);
        for (var m : PatternMatcher.string(ctx.input, needles, VIDEO_PROFILE_NAME, opts)) {
            ctx.matches.add(new Match(VIDEO_PROFILE_NAME,value, m.start(), m.end(), m.raw(),
                m.priority(), Set.of(VIDEO_PROFILE_RULE_TAG), m.isPrivate()));
        }
    }
    private void addRegexProfileTagged(ParseContext ctx,
                                       Predicate<Match> v) {
        var opts = RegexOpts.defaults().withValidator(v).withValue(_ -> "Advanced Video Codec High Definition");
        for (var m : PatternMatcher.regex(ctx.input, AVC_HD, VIDEO_PROFILE_NAME, opts)) {
            ctx.matches.add(new Match(VIDEO_PROFILE_NAME,"Advanced Video Codec High Definition", m.start(), m.end(), m.raw(),
                m.priority(), Set.of(VIDEO_PROFILE_RULE_TAG), m.isPrivate()));
        }
    }
}
