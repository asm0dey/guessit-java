package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class OtherExtractor implements Extractor {
    @Override public String name() { return "other"; }
    @Override public int priority() { return 1000; }

    @Override
    @SuppressWarnings("unchecked")
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("other");
        var inner = section.get("other");
        if (!(inner instanceof Map<?, ?> entries)) return;

        var input = ctx.input;
        for (var e : entries.entrySet()) {
            var value = String.valueOf(e.getKey());
            if (value.startsWith("_")) continue;
            for (var pattern : flatten(e.getValue())) {
                emit(ctx, input, value, pattern);
            }
        }
    }

    private static List<Object> flatten(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf((List<Object>) l);
        return List.of(v);
    }

    @SuppressWarnings("unchecked")
    private static void emit(ParseContext ctx, String input, String value, Object pattern) {
        if (pattern instanceof String s) {
            if (s.startsWith("re:")) emitRegex(ctx, input, value, s.substring(3));
            else emitString(ctx, input, value, s);
        } else if (pattern instanceof Map<?, ?> m) {
            var stringList = m.get("string");
            var regexList = m.get("regex");
            if (stringList instanceof String s) emitString(ctx, input, value, s);
            else if (stringList instanceof List<?> l) for (var p : l) emitString(ctx, input, value, p.toString());
            if (regexList instanceof String s) emitRegex(ctx, input, value, s);
            else if (regexList instanceof List<?> l) for (var p : l) emitRegex(ctx, input, value, p.toString());
        }
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle) {
        var opts = StringOpts.defaults()
            .withValidator(Validators.sepsSurround(input));
        var idxFrom = 0;
        var hay = input.toLowerCase(java.util.Locale.ROOT);
        var n = needle.toLowerCase(java.util.Locale.ROOT);
        while (true) {
            int i = hay.indexOf(n, idxFrom);
            if (i < 0) break;
            int end = i + n.length();
            var raw = input.substring(i, end);
            var m = new Match("other", value, i, end, raw, opts.priority(), Set.of(), false);
            if (opts.validator().test(m)) ctx.matches.add(m);
            idxFrom = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignore) { return; }
        var matcher = p.matcher(input);
        var validator = Validators.sepsSurround(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var m = new Match("other", value, s, e, input.substring(s, e), 1000, Set.of(), false);
            if (validator.test(m)) ctx.matches.add(m);
        }
    }
}
