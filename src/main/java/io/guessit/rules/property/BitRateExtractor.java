package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.util.BitRate;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts bit rate values from filenames.
 *
 * <p>All matches are initially tagged as {@code audio_bit_rate}. A subsequent
 * {@link BitRateTypeRule} (registered in {@link io.guessit.rules.Rules}) promotes matches
 * to {@code video_bit_rate} when they are preceded by a video-context match
 * ({@code screen_size}, {@code source}, or {@code video_codec}) with no non-separator gap.
 *
 * <p>This mirrors Python guessit's {@code bit_rate.py} / {@code BitRateTypeRule}.
 */
public final class BitRateExtractor implements Extractor {
    private static final Pattern P = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?-?[kmg]b(?:ps|its?))");

    @Override public String name() { return "audio_bit_rate"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = P.matcher(input);
        while (m.find()) {
            var head = new Match("audio_bit_rate", null, m.start(1), m.end(1), m.group(1), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var raw = m.group(1);
            ctx.matches.add(new Match("audio_bit_rate", BitRate.fromString(raw), m.start(1), m.end(1), raw,
                priority(), Set.of("release-group-prefix"), false));
        }
    }
}
