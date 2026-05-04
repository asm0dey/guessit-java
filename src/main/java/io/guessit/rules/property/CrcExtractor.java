package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects 8-hex-digit CRC32 values, e.g. {@code [ABCD1234]} or {@code .ABCD1234.},
 * plus UUID/hash-like id numbers via the same heuristic used by python guessit
 * ({@code guess_idnumber}).
 *
 * <p>Priority is 500 (lower than season/episode at 1000) so ConflictPhase will
 * favour season/episode over crc32 when they overlap. uuid uses the default
 * priority but its conflict_solver in python keeps the uuid; here we lower it
 * just enough to lose to strong matches but win against bare digit weak ones.
 */
public final class CrcExtractor implements Extractor {
    private static final Pattern CRC = Pattern.compile("(?i)([0-9a-f]{8})");
    private static final Pattern UUID = Pattern.compile("([a-zA-Z0-9-]{20,})");
    private static final Pattern SXX_EXX_INSIDE = Pattern.compile("(?i)S\\d{1,3}E\\d{1,3}");

    @Override public String name() { return "crc32"; }
    @Override public int priority() { return 500; }

    @Override
    public void extract(ParseContext ctx) {
        extractCrc32(ctx);
        extractUuid(ctx);
    }

    private void extractCrc32(ParseContext ctx) {
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

    private void extractUuid(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = UUID.matcher(input);
        while (m.find()) {
            var raw = m.group(1);
            if (!isLikelyIdNumber(raw)) continue;
            // Skip uuid candidates that swallow an SxxExx season+episode token.
            // The COEXIST tag on season/episode means ConflictSolver leaves the
            // uuid in place, so without this guard a string like
            // "S01E01E07-FooBar-Group" becomes uuid AND blocks episode_title.
            if (SXX_EXX_INSIDE.matcher(raw).find()) continue;
            var head = new Match("uuid", null, m.start(1), m.end(1), raw, priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            ctx.matches.add(new Match("uuid", raw,
                m.start(1), m.end(1), raw, priority(), Set.of(), false));
        }
    }

    /**
     * Heuristic: count character-type switches (digit/letter/other) and
     * adjacent-letter switches; accept when both ratios exceed 0.4. Mirrors
     * python {@code guess_idnumber}.
     */
    private static boolean isLikelyIdNumber(String s) {
        final int DIGIT = 0, LETTER = 1, OTHER = 2;
        int last = LETTER;
        int switchCount = 0;
        int switchLetterCount = 0;
        int letterCount = 0;
        char lastLetter = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int ci;
            if (c >= '0' && c <= '9') {
                ci = DIGIT;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                ci = LETTER;
                if (c != lastLetter) switchLetterCount++;
                lastLetter = c;
                letterCount++;
            } else {
                ci = OTHER;
            }
            if (ci != last) switchCount++;
            last = ci;
        }
        double switchRatio = (double) switchCount / s.length();
        double lettersRatio = letterCount == 0 ? 1.0 : (double) switchLetterCount / letterCount;
        return switchRatio > 0.4 && lettersRatio > 0.4;
    }
}
