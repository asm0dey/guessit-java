package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

public final class CdExtractor implements Extractor {
    private static final Pattern CD_OF = Pattern.compile(
        "(?i)cd-?(?<cd>\\d+)(?:-?of-?(?<count>\\d+))?");
    private static final Pattern CD_COUNT = Pattern.compile(
        "(?i)(?<count>\\d+)-?cds?");

    @Override public String name() { return "cd"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);

        var m = CD_OF.matcher(input);
        while (m.find()) {
            var head = new Match("cd", null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var cd = Integer.parseInt(m.group("cd"));
            if (cd <= 0 || cd >= 100) continue;
            ctx.matches.add(new Match("cd", cd,
                m.start("cd"), m.end("cd"), m.group("cd"), priority(), Set.of(), false));
            if (m.group("count") != null) {
                var c = Integer.parseInt(m.group("count"));
                if (c > 0 && c < 100) {
                    ctx.matches.add(new Match("cd_count", c,
                        m.start("count"), m.end("count"), m.group("count"),
                        priority(), Set.of(), false));
                }
            }
        }

        m = CD_COUNT.matcher(input);
        while (m.find()) {
            var head = new Match("cd_count", null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var c = Integer.parseInt(m.group("count"));
            if (c <= 0 || c >= 100) continue;
            ctx.matches.add(new Match("cd_count", c,
                m.start("count"), m.end("count"), m.group("count"),
                priority(), Set.of(), false));
        }
    }
}
