package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Extracts {@code video_codec} (H.264, H.265, MPEG-2, …) and the related
 * {@code video_profile}, {@code video_api}, and {@code color_depth} properties.
 */
public final class VideoCodecExtractor implements Extractor {

    public static final String VIDEO_PROFILE = "video_profile";
    public static final String VIDEO_CODEC = "video_codec";

    private static final String VIDEO_PROFILE_RULE_TAG = "video_profile.rule";
    private static final MatchName VIDEO_CODEC_NAME = MatchName.VIDEO_CODEC;
    private static final MatchName VIDEO_PROFILE_NAME = MatchName.VIDEO_PROFILE;
    private static final MatchName COLOR_DEPTH_NAME = MatchName.COLOR_DEPTH;
    private static final MatchName VIDEO_API_NAME = MatchName.VIDEO_API;

    @Override
    public String name() {
        return VIDEO_CODEC;
    }

    @Override
    public String description() {
        return "video codec (x264, x265, h264, h265, xvid, divx, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var sepsAround = Validators.sepsBefore(input).or(Validators.sepsAfter(input));

        extractCodecs(ctx, sepsAround);
        extractHevc10(ctx);
        extractProfiles(ctx, sepsAround);
        extractColorDepths(ctx, sepsAround);

        // video_api
        var apiOpts = StringOpts.defaults().withValidator(sepsAround);
        for (var m : PatternMatcher.string(input, Set.of("DXVA"), VIDEO_API_NAME, apiOpts, ctx.trace)) {
            ctx.matches.add(new Match(VIDEO_API_NAME, "DXVA", m.start(), m.end(), m.raw(), m.priority(), m.tags(), false));
        }
    }

    private void extractCodecs(ParseContext ctx, Predicate<Match> validator) {
        var optsBase = RegexOpts.defaults().withValidator(validator);
        var tags = Set.of("source-suffix", "streaming_service.suffix");

        for (var rule : VideoCodecRules.CODEC_RULES) {
            var opts = optsBase.withValue(_ -> rule.value());
            for (var m : PatternMatcher.regex(ctx.input, rule.pattern(), VIDEO_CODEC_NAME, opts, ctx.trace)) {
                ctx.matches.add(new Match(VIDEO_CODEC_NAME, m.value(), m.start(), m.end(), m.raw(), m.priority(), tags, false));
            }
        }
    }

    private void extractHevc10(ParseContext ctx) {
        var m = VideoCodecRules.P_HEVC10.matcher(ctx.input);
        while (m.find()) {
            int cStart = m.start(VideoCodecRules.GRP_C);
            int cEnd = m.end(VideoCodecRules.GRP_C);
            int dStart = m.start(VideoCodecRules.GRP_D);
            int dEnd = m.end(VideoCodecRules.GRP_D);
            String cRaw = m.group(VideoCodecRules.GRP_C);
            String dRaw = m.group(VideoCodecRules.GRP_D);

            var dummy = new Match(MatchName.DUMMY, "", cStart, cEnd, cRaw, 0, Set.of(), false);
            if (Validators.sepsBefore(ctx.input).test(dummy)) {
                ctx.matches.named(VIDEO_CODEC_NAME)
                        .filter(e -> e.start() == cStart && e.end() < cEnd)
                        .toList()
                        .forEach(ctx.matches::remove);

                ctx.matches.add(new Match(VIDEO_CODEC_NAME, "H.265", cStart, cEnd, cRaw, 1000,
                        Set.of("source-suffix", "streaming_service.suffix"), false));

                ctx.matches.add(new Match(COLOR_DEPTH_NAME, "10-bit", dStart, dEnd, dRaw, 1000,
                        Set.of("video-codec-suffix", "derivedFrom:video_codec"), false));
            }
        }
    }

    private void extractProfiles(ParseContext ctx, Predicate<Match> validator) {
        var strOptsBase = StringOpts.defaults().withValidator(validator);
        var tagsTagged = Set.of(VIDEO_PROFILE_RULE_TAG);

        for (var rule : VideoCodecRules.PROFILE_STR_RULES) {
            for (var m : PatternMatcher.string(ctx.input, rule.aliases(), VIDEO_PROFILE_NAME, strOptsBase, ctx.trace)) {
                ctx.matches.add(new Match(VIDEO_PROFILE_NAME, rule.value(), m.start(), m.end(), m.raw(), m.priority(), tagsTagged, false));
            }
        }

        var regexOptsBase = RegexOpts.defaults().withValidator(validator);
        for (var rule : VideoCodecRules.PROFILE_REGEX_RULES) {
            var opts = regexOptsBase.withValue(_ -> rule.value());
            for (var m : PatternMatcher.regex(ctx.input, rule.pattern(), VIDEO_PROFILE_NAME, opts, ctx.trace)) {
                var tags = rule.isTagged() ? tagsTagged : Set.<String>of();
                ctx.matches.add(new Match(VIDEO_PROFILE_NAME, rule.value(), m.start(), m.end(), m.raw(), m.priority(), tags, false));
            }
        }
    }

    private void extractColorDepths(ParseContext ctx, Predicate<Match> validator) {
        var optsBase = RegexOpts.defaults().withValidator(validator);
        for (var rule : VideoCodecRules.COLOR_DEPTH_RULES) {
            var opts = optsBase.withValue(_ -> rule.value());
            for (var m : PatternMatcher.regex(ctx.input, rule.pattern(), COLOR_DEPTH_NAME, opts, ctx.trace)) {
                ctx.matches.add(m);
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        validateVideoCodec(ctx);

        boolean hasCodec = ctx.matches.named(VIDEO_CODEC_NAME).findAny().isPresent();
        if (!hasCodec) {
            var toRemove = ctx.matches.named(VIDEO_PROFILE_NAME)
                    .filter(p -> p.tags().contains(VIDEO_PROFILE_RULE_TAG))
                    .toList();
            toRemove.forEach(ctx.matches::remove);
        }
    }

    private void validateVideoCodec(ParseContext ctx) {
        var sepsBefore = Validators.sepsBefore(ctx.input);
        var sepsAfter = Validators.sepsAfter(ctx.input);

        var prefixSpans = ctx.matches.all().filter(m -> m.tags().contains("video-codec-prefix")).toList();
        var suffixSpans = ctx.matches.all().filter(m -> m.tags().contains("video-codec-suffix")).toList();

        var toRemove = ctx.matches.named(VIDEO_CODEC_NAME)
                .filter(codec -> {
                    boolean before = sepsBefore.test(codec) || prefixSpans.stream().anyMatch(m -> m.end() == codec.start());
                    boolean after = sepsAfter.test(codec) || suffixSpans.stream().anyMatch(m -> m.start() == codec.end());
                    return !(before && after);
                }).toList();

        toRemove.forEach(ctx.matches::remove);
    }
}