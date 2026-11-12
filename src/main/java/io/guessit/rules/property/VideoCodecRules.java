package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.SiftPatterns;
import com.mirkoddd.sift.core.dsl.Connector;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Dictionary holding all the data-driven rules and pre-compiled Sift patterns
 * for the {@link VideoCodecExtractor}.
 */
final class VideoCodecRules {

    private VideoCodecRules() {
    }

    // don't mind me, just having fun with Sift
    private static SiftPattern<Fragment> anyOfLiterals(String... literals) {
        var options = Arrays.stream(literals)
                .map(SiftPatterns::literal)
                .toList();

        return anyOf(options);
    }

    @SafeVarargs
    private static Pattern anyOfPatterns(SiftPattern<Fragment> p1, SiftPattern<Fragment>p2, SiftPattern<Fragment>... patterns) {
        return patternFrom(anyOf(p1, p2, patterns));
    }

    private static Connector<Fragment> optionalLiteral(String text) {
        return optional().of(literal(text));
    }

    private static Connector<Fragment> optionallyFind(SiftPattern<Fragment> fragment) {
        return optional().of(fragment);
    }

    private static Connector<Fragment> find(String text) {
        return fromAnywhere().of(literal(text));
    }

    private static Connector<Fragment> find(SiftPattern<Fragment> fragment) {
        return fromAnywhere().of(fragment);
    }

    private static Pattern patternFrom(SiftPattern<Fragment> frag) {
        return Pattern.compile(filteringWith(SiftGlobalFlag.CASE_INSENSITIVE).fromAnywhere().of(frag).shake());
    }

    private static RegexRule rule(Pattern p, String val) { return new RegexRule(p, val, false); }
    private static RegexRule tagged(Pattern p, String val) { return new RegexRule(p, val, true); }
    private static StrRule aliases(String val, String... keys) { return new StrRule(Set.of(keys), val); }

    private static final SiftPattern<Fragment> OPT_DASH = optionallyFind(anyOfLiterals(" ", ".", "_", "-"));

    private static final SiftPattern<Fragment> HX = anyOfLiterals("h", "x");

    private static final SiftPattern<Fragment> MPEG = find("Mp")
            .followedBy(optionalLiteral("e")).followedBy('g');

    private static final SiftPattern<Fragment> BITS = find(OPT_DASH)
            .followedBy(literal("bit"))
            .followedBy(optionalLiteral("s"));

    private static final SiftPattern<Fragment> OPT_MPEG4 = optionallyFind(find("MPEG").followedBy(OPT_DASH).followedBy('4'));

    private static final Pattern P_RV = patternFrom(find("Rv").then().exactly(2).digits());
    private static final Pattern P_DIVX = patternFrom(anyOfLiterals("DVDivX", "DivX"));
    private static final Pattern P_XVID = patternFrom(literal("XviD"));
    private static final Pattern P_VP7 = patternFrom(literal("VP7"));
    private static final Pattern P_VP9 = patternFrom(literal("VP9"));
    private static final Pattern P_VC1 = patternFrom(find("VC").followedBy(OPT_DASH).followedBy('1'));
    private static final Pattern P_VP8 = patternFrom(find("VP8").followedBy(optionalLiteral("0")));
    private static final Pattern P_H263 = patternFrom(find(HX).followedBy(OPT_DASH, literal("263")));

    private static final Pattern P_H265 = anyOfPatterns(
            find(HX).followedBy(OPT_DASH, literal("265")),
            literal("HEVC")
    );

    private static final Pattern P_MPEG2 = anyOfPatterns(
            find(MPEG).followedBy(OPT_DASH).followedBy('2'),
            find(HX).followedBy(OPT_DASH, literal("262"))
    );

    private static final Pattern P_H264 = anyOfPatterns(
            find(HX).followedBy(OPT_DASH, literal("264")),
            find(OPT_MPEG4).followedBy(literal("AVC"), optionalLiteral("HD"))
    );

    private static final Pattern P_HI422P = patternFrom(literal("Hi422P"));
    private static final Pattern P_HI444PP = patternFrom(literal("Hi444PP"));
    private static final Pattern P_HI10P = patternFrom(find("Hi10").followedBy(optionalLiteral("P")));
    private static final Pattern P_AVCHD = patternFrom(find("AVC").followedBy(optionalLiteral("HD")));

    private static final Pattern P_12BIT = patternFrom(find("12").followedBy(BITS));
    private static final Pattern P_8BIT = patternFrom(find("8").followedBy(BITS));
    private static final Pattern P_10BIT = anyOfPatterns(
            find("10").followedBy(BITS),
            literal("YUV420P10"),
            find("Hi10").followedBy(optionalLiteral("P"))
    );

    static final String GRP_C = "c";
    static final String GRP_D = "d";

    static final Pattern P_HEVC10 = Pattern.compile(filteringWith(SiftGlobalFlag.CASE_INSENSITIVE).fromAnywhere()
            .namedCapture(capture(GRP_C, literal("hevc")))
            .then()
            .namedCapture(capture(GRP_D, literal("10")))
            .shake());

    record RegexRule(Pattern pattern, String value, boolean isTagged) {}

    record StrRule(Set<String> aliases, String value) {}

    static final List<RegexRule> CODEC_RULES = List.of(
            rule(P_RV, "RealVideo"),
            rule(P_MPEG2, "MPEG-2"),
            rule(P_DIVX, "DivX"),
            rule(P_XVID, "Xvid"),
            rule(P_VC1, "VC-1"),
            rule(P_VP7, "VP7"),
            rule(P_VP8, "VP8"),
            rule(P_VP9, "VP9"),
            rule(P_H263, "H.263"),
            rule(P_H264, "H.264"),
            rule(P_H265, "H.265")
    );

    static final List<StrRule> PROFILE_STR_RULES = List.of(
            aliases("Baseline", "BP"),
            aliases("Extended", "XP", "EP"),
            aliases("Main", "MP"),
            aliases("High", "HP", "HiP"),
            aliases("Scalable Video Coding", "SC", "SVC"),
            aliases("High Efficiency Video Coding", "HEVC")
    );

    static final List<RegexRule> PROFILE_REGEX_RULES = List.of(
            tagged(P_AVCHD, "Advanced Video Codec High Definition"),
            rule(P_HI422P, "High 4:2:2"),
            rule(P_HI444PP, "High 4:4:4 Predictive"),
            rule(P_HI10P, "High 10")
    );

    static final List<RegexRule> COLOR_DEPTH_RULES = List.of(
            rule(P_12BIT, "12-bit"),
            rule(P_10BIT, "10-bit"),
            rule(P_8BIT, "8-bit")
    );
}