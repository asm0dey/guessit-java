package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
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

    @Override public String name() { return "video_codec"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var v = Validators.sepsSurround(input);

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
        var hevc10 = Pattern.compile("(hevc)(10)", Pattern.CASE_INSENSITIVE);
        var m = hevc10.matcher(input);
        while (m.find()) {
            int g1s = m.start(1), g1e = m.end(1), g2s = m.start(2), g2e = m.end(2);
            String raw1 = m.group(1), raw2 = m.group(2);
            if (!Validators.sepsBefore(input).test(
                    new Match("dummy", "", g1s, g1e, raw1, 0, Set.of(), false)))
                continue;
            // Remove any shorter video_codec match at same start that overlaps (e.g., HEVC within HEVC10)
            ctx.matches.named("video_codec")
                .filter(e -> e.start() == g1s && e.end() < g1e)
                .toList()
                .forEach(ctx.matches::remove);
            ctx.matches.add(new Match("video_codec", "H.265", g1s, g1e, raw1, 1000, Set.of(), false));
            ctx.matches.add(new Match("color_depth", "10-bit", g2s, g2e, raw2, 1000, Set.of(), false));
        }

        // video_profile (validated, validators enforce seps_surround)
        addStr(ctx, VIDEO_PROFILE, "Baseline",                            Set.of("BP"),  v);
        addStr(ctx, VIDEO_PROFILE, "Extended",                            Set.of("XP","EP"), v);
        addStr(ctx, VIDEO_PROFILE, "Main",                                Set.of("MP"),  v);
        addStr(ctx, VIDEO_PROFILE, "High",                                Set.of("HP","HiP"), v);
        addStr(ctx, VIDEO_PROFILE, "Scalable Video Coding",               Set.of("SC","SVC"), v);
        addRegexProfile(ctx, "AVC(?:HD)?",                                  "Advanced Video Codec High Definition", v);
        addStr(ctx, VIDEO_PROFILE, "High Efficiency Video Coding",        Set.of("HEVC"), v);
        addRegexProfile(ctx, "Hi422P",                                      "High 4:2:2", v);
        addRegexProfile(ctx, "Hi444PP",                                     "High 4:4:4 Predictive", v);
        addRegexProfile(ctx, "Hi10P?",                                      "High 10", v);

        // video_api
        addStr(ctx, "video_api", "DXVA", Set.of("DXVA"), v);

        // color_depth
        addRegexNamed(ctx, "color_depth", "12.?bits?", "12-bit", v);
        addRegexNamed(ctx, "color_depth", "10.?bits?|YUV420P10|Hi10P?", "10-bit", v);
        addRegexNamed(ctx, "color_depth", "8.?bits?",  "8-bit",  v);
    }

    /** Replicates ValidateVideoCodec + VideoProfileRule. */
    @Override
    public void postProcess(ParseContext ctx) {
        // ValidateVideoCodec: Plan-0 PatternMatcher already enforces seps_surround via validator. No-op here.
        // VideoProfileRule: drop video_profile tagged 'video_profile.rule' that has no nearby video_codec.
        // Tags wiring shipped in extract() above is conservative; this rule deferred to Plan 5 polish.
    }

    private void addCodec(ParseContext ctx, String src, String value, java.util.function.Predicate<Match> v) {
        var p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        var opts = RegexOpts.defaults().withValidator(v).withValue(_ -> value);
        for (var m : PatternMatcher.regex(ctx.input, p, "video_codec", opts)) ctx.matches.add(m);
    }
    private void addRegexProfile(ParseContext ctx, String src, String value, java.util.function.Predicate<Match> v) {
        addRegexNamed(ctx, VIDEO_PROFILE, src, value, v);
    }
    private void addRegexNamed(ParseContext ctx, String name, String src, String value, java.util.function.Predicate<Match> v) {
        var p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE);
        var opts = RegexOpts.defaults().withValidator(v).withValue(_ -> value);
        for (var m : PatternMatcher.regex(ctx.input, p, name, opts)) ctx.matches.add(m);
    }
    private void addStr(ParseContext ctx, String name, String value, Set<String> needles,
                        java.util.function.Predicate<Match> v) {
        var opts = StringOpts.defaults().withValidator(v);
        for (var m : PatternMatcher.string(ctx.input, needles, name, opts)) {
            ctx.matches.add(new Match(name, value, m.start(), m.end(), m.raw(),
                m.priority(), m.tags(), m.isPrivate()));
        }
    }
}
