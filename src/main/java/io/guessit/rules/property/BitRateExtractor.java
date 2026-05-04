package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.util.BitRate;

import java.util.Set;
import java.util.regex.Pattern;

public final class BitRateExtractor implements Extractor {
    private static final Pattern P = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?-?[kmg]b(?:ps|its?))");

    @Override public String name() { return "bit_rate"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = P.matcher(input);
        while (m.find()) {
            var head = new Match("bit_rate", null, m.start(1), m.end(1), m.group(1), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var raw = m.group(1);
            ctx.matches.add(new Match("bit_rate", BitRate.fromString(raw), m.start(1), m.end(1), raw,
                priority(), Set.of("release-group-prefix"), false));
        }
    }
}
