package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import io.guessit.engine.*;
import io.guessit.util.Size;

import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Extracts {@code size} (123MB, 4.5GB, …).
 */
public final class SizeExtractor implements Extractor {

    public static final String EXTRACTOR_NAME = "size";
    private static final String GRP_SIZE = "val";
    private static final String TAG_RELEASE_GROUP_PREFIX = "release-group-prefix";

    private static final Pattern PATTERN = buildPattern();

    private static Pattern buildPattern() {
        var decimalPart = exactly(1).character('.')
                .then().oneOrMore().digits();

        var units = anyOf(
                literal("mb"),
                literal("gb"),
                literal("tb")
        );

        var sizeValue = oneOrMore().digits()
                .then().optional().of(decimalPart)
                .then().optional().character('-')
                .then().of(units);

        var sift = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere()
                .namedCapture(capture(GRP_SIZE, sizeValue));

        return Pattern.compile(sift.shake());
    }

    @Override
    public String name() {
        return EXTRACTOR_NAME;
    }

    @Override
    public String description() {
        return "size (123MB, 4.5GB, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);

        while (m.find()) {
            var raw = m.group(GRP_SIZE);
            var head = new Match(MatchName.SIZE, null, m.start(GRP_SIZE), m.end(GRP_SIZE), raw, priority(), Set.of(), false);

            if (seps.test(head)) {
                ctx.matches.add(new Match(MatchName.SIZE, Size.fromString(raw), m.start(GRP_SIZE), m.end(GRP_SIZE), raw,
                        priority(), Set.of(TAG_RELEASE_GROUP_PREFIX), false));
            }
        }
    }
}