package io.guessit.rules.property;

import com.mirkoddd.sift.core.NamedCapture;
import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Detects part numbers from {@code (pt|part)-?\d+} or {@code (pt|part)-?[Roman numeral]}
 * patterns, e.g. {@code Part2}, {@code pt3}, {@code PartIII}.
 *
 * <p>Value range: 0 &lt; value &lt; 100. Matches must be surrounded by separators.
 */
public final class PartExtractor implements Extractor {

    private static final NamedCapture N_GROUP = capture("n", Numerals.NUMERAL_PATTERN);

    private static final SiftPattern<Fragment> PART_BASE_PATTERN = Sift.fromAnywhere()
            .of(anyOf(literal("pt"), literal("part")))
            .then().optional().of(Abbreviations.SEPS_NO_FS_PATTERN)
            .then().namedCapture(N_GROUP);

    private static final SiftPattern<Fragment> SIFT_RULE = withFlags(PART_BASE_PATTERN, SiftGlobalFlag.CASE_INSENSITIVE);

    private static final Pattern P = Pattern.compile(SIFT_RULE.shake());

    @Override public String name() { return "part"; }

    @Override
    public String description() {
        return "part number (Part 1, Pt II, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = P.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.PART, null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;

            var raw = m.group("n");
            int v;
            try {
                v = Numerals.parse(raw, true, true, true);
            } catch (IllegalArgumentException _) {
                continue;
            }
            if (v <= 0 || v >= 100) continue;

            // Span covers the whole "part N" so the prefix word doesn't leak
            // into the surrounding title hole.
            ctx.matches.add(new Match(MatchName.PART, v,
                m.start(), m.end(), m.group(), priority(), Set.of(), false));
        }
    }
}
