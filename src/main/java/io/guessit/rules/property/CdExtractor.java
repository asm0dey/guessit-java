package io.guessit.rules.property;

import com.mirkoddd.sift.core.NamedCapture;
import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.oneOrMore;
import static com.mirkoddd.sift.core.SiftPatterns.*;

public final class CdExtractor implements Extractor {
    public static final String COUNT = "count";
    private static final String CD = "cd";

    private static final NamedCapture CD_GROUP = capture(CD, oneOrMore().digits());
    private static final NamedCapture COUNT_GROUP = capture(COUNT, oneOrMore().digits());

    private static final SiftPattern<Fragment> SEP = Sift.fromAnywhere()
            .optional().of(Abbreviations.SEPS_NO_FS_PATTERN);

    private static final SiftPattern<Fragment> OF_BLOCK = Sift.fromAnywhere()
            .of(SEP)
            .then().of(literal("of"))
            .then().of(SEP)
            .then().namedCapture(COUNT_GROUP);

    private static final SiftPattern<Fragment> CD_OF_BASE = Sift.fromAnywhere()
            .of(literal("cd"))
            .then().of(SEP)
            .then().namedCapture(CD_GROUP)
            .then().optional().of(OF_BLOCK);

    private static final Pattern CD_OF = Pattern.compile(
            withFlags(CD_OF_BASE, SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    private static final SiftPattern<Fragment> CDS_LITERAL = Sift.fromAnywhere()
            .of(literal("cd"))
            .then().optional().character('s');

    private static final SiftPattern<Fragment> CD_COUNT_BASE = Sift.fromAnywhere()
            .namedCapture(COUNT_GROUP)
            .then().of(SEP)
            .then().of(CDS_LITERAL);

    private static final Pattern CD_COUNT = Pattern.compile(
            withFlags(CD_COUNT_BASE, SiftGlobalFlag.CASE_INSENSITIVE).shake()
    );

    @Override public String name() { return "cd"; }

    @Override
    public String description() {
        return "CD / disc number tokens (CD1, CD2, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        scanCdOf(ctx, input, seps);
        scanCdCount(ctx, input, seps);
    }

    private void scanCdOf(ParseContext ctx, String input, java.util.function.Predicate<Match> seps) {
        var m = CD_OF.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.CD, null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var cd = Integer.parseInt(m.group("cd"));
            if (cd <= 0 || cd >= 100) continue;
            ctx.matches.add(new Match(MatchName.CD, cd,
                m.start("cd"), m.end("cd"), m.group("cd"), priority(), Set.of(), false));
            addCdCountIfPresent(ctx, m);
        }
    }

    private void addCdCountIfPresent(ParseContext ctx, java.util.regex.Matcher m) {
        if (m.group(COUNT) == null) return;
        var c = Integer.parseInt(m.group(COUNT));
        if (c <= 0 || c >= 100) return;
        ctx.matches.add(new Match(MatchName.CD_COUNT, c,
            m.start(COUNT), m.end(COUNT), m.group(COUNT),
            priority(), Set.of(), false));
    }

    private void scanCdCount(ParseContext ctx, String input, java.util.function.Predicate<Match> seps) {
        var m = CD_COUNT.matcher(input);
        while (m.find()) {
            var head = new Match(MatchName.CD_COUNT, null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var c = Integer.parseInt(m.group(COUNT));
            if (c <= 0 || c >= 100) continue;
            ctx.matches.add(new Match(MatchName.CD_COUNT, c,
                m.start(COUNT), m.end(COUNT), m.group(COUNT),
                priority(), Set.of(), false));
            addCdLiteralMarker(ctx, input, m);
        }
    }

    /** Cover the trailing "cd"/"cds" literal with a private marker so
     *  it doesn't leak into a title/alt-title hole. */
    private void addCdLiteralMarker(ParseContext ctx, String input, java.util.regex.Matcher m) {
        int litStart = m.end(COUNT);
        int litEnd = m.end();
        while (litStart < litEnd && Seps.isSep(input.charAt(litStart))) litStart++;
        if (litEnd > litStart) {
            ctx.matches.add(new Match(MatchName.CD_MARKER, null, litStart, litEnd,
                input.substring(litStart, litEnd), priority(), Set.of(), true));
        }
    }
}
