package io.guessit.rules.property;

import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Validators;
import io.guessit.util.BitRate;
import com.mirkoddd.sift.core.SiftGlobalFlag;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;
import static com.mirkoddd.sift.core.SiftPatterns.capture;
import static com.mirkoddd.sift.core.SiftPatterns.literal;

/**
 * Extracts bit rate values from filenames.
 *
 * <p>All matches are initially tagged as {@code audio_bit_rate}. A subsequent
 * {@link io.guessit.rules.post.BitRateTypeRule} promotes matches
 * to {@code video_bit_rate} when they are preceded by a video-context match.
 */
public final class BitRateExtractor implements Extractor {

    private static final String GRP_RAW = "raw";
    private static final String TAG_WEAK_AUDIO_CHANNELS = "weak-audio_channels";
    private static final String TAG_RELEASE_GROUP_PREFIX = "release-group-prefix";

    private static final Pattern[] PATTERNS = buildPatterns();

    private static Pattern[] buildPatterns() {
        var sep = anyOf(literal(" "), literal("."), literal("_"), literal("-"));
        var optSep = optional().of(sep);

        var multiplier = anyOf(literal("k"), literal("m"), literal("g"));

        var bpsWords = anyOf(literal("ps"), literal("its"), literal("it"));
        var baseUnit = exactly(1).character('b').followedBy(bpsWords);

        var suffix = fromAnywhere()
                .of(optSep)
                .followedBy(List.of(multiplier, baseUnit));

        var intPattern = oneOrMore().digits().followedBy(suffix);

        var decPattern = oneOrMore().digits()
                .followedBy('.')
                .then().oneOrMore().digits()
                .followedBy(suffix);

        var pInt = patternFromFragment(intPattern);
        var pDec = patternFromFragment(decPattern);

        return new Pattern[]{ pInt, pDec };
    }

    private static Pattern patternFromFragment(SiftPattern<Fragment> fragment) {
        var pattern = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere().namedCapture(capture(GRP_RAW, fragment));
        return Pattern.compile(pattern.shake());
    }

    @Override
    public String name() {
        return "audio_bit_rate";
    }

    @Override
    public String description() {
        return "video / audio bit rate (kbps, Mbps)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        var channels = ctx.matches.named(MatchName.AUDIO_CHANNELS)
                .filter(m -> !m.tags().contains(TAG_WEAK_AUDIO_CHANNELS))
                .toList();

        for (var pattern : PATTERNS) {
            var matcher = pattern.matcher(input);
            while (matcher.find()) {
                tryAddBitRate(ctx, matcher, seps, channels);
            }
        }
    }

    private void tryAddBitRate(ParseContext ctx, Matcher matcher, Predicate<Match> seps, List<Match> channels) {
        int start = matcher.start(GRP_RAW);
        int end = matcher.end(GRP_RAW);
        String raw = matcher.group(GRP_RAW);

        var head = new Match(MatchName.AUDIO_BIT_RATE, null, start, end, raw, priority(), Set.of(), false);
        if (!seps.test(head)) return;

        if (overlapsAny(start, end, channels)) return;

        ctx.matches.add(new Match(MatchName.AUDIO_BIT_RATE, BitRate.fromString(raw), start, end, raw,
                priority(), Set.of(TAG_RELEASE_GROUP_PREFIX), false));
    }

    private static boolean overlapsAny(int start, int end, List<Match> spans) {
        return spans.stream().anyMatch(sp -> start < sp.end() && sp.start() < end);
    }
}