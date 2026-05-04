package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects 8-hex-digit CRC32 values, e.g. {@code [ABCD1234]} or {@code .ABCD1234.}.
 *
 * <p>Priority is 500 (lower than season/episode at 1000) so ConflictPhase will
 * favour season/episode over crc32 when they overlap.
 */
public final class CrcExtractor implements Extractor {
    private static final Pattern CRC = Pattern.compile("(?i)([0-9a-f]{8})");

    @Override public String name() { return "crc32"; }
    @Override public int priority() { return 500; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = CRC.matcher(input);
        while (m.find()) {
            var head = new Match("crc32", null, m.start(1), m.end(1), m.group(1), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            ctx.matches.add(new Match("crc32", m.group(1),
                m.start(1), m.end(1), m.group(1), priority(), Set.of(), false));
        }
    }
}
