package io.guessit.rules.property;

import io.guessit.engine.Extractor;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Validators;
import io.guessit.util.Size;

import java.util.Set;
import java.util.regex.Pattern;

public final class SizeExtractor implements Extractor {
    private static final Pattern P = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?-?[mgt]b)");

    @Override public String name() { return "size"; }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = P.matcher(input);
        while (m.find()) {
            var head = new Match("size", null, m.start(1), m.end(1), m.group(1), priority(), Set.of(), false);
            if (!seps.test(head)) continue;
            var raw = m.group(1);
            ctx.matches.add(new Match("size", Size.fromString(raw), m.start(1), m.end(1), raw,
                priority(), Set.of("release-group-prefix"), false));
        }
    }
}
