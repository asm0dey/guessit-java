package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class StreamingServiceExtractor implements Extractor {
    @Override public String name() { return "streaming_service"; }
    @Override public int priority() { return 1000; }

    @Override
    public void extract(ParseContext ctx) {
        var section = ctx.config.section("streaming_service");
        if (section.isEmpty()) return;

        var input = ctx.input;
        for (var e : section.entrySet()) {
            String value = String.valueOf(e.getKey());
            for (var pat : flatten(e.getValue())) emit(ctx, input, value, pat);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> flatten(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return List.copyOf((List<Object>) l);
        return List.of(v);
    }

    private static void emit(ParseContext ctx, String input, String value, Object pat) {
        if (pat instanceof String s) {
            if (s.startsWith("re:")) emitRegex(ctx, input, value, s.substring(3));
            else emitString(ctx, input, value, s);
        } else if (pat instanceof Map<?, ?> m) {
            var s = m.get("string");
            var r = m.get("regex");
            if (s instanceof String str) emitString(ctx, input, value, str);
            else if (s instanceof List<?> l) for (var x : l) emitString(ctx, input, value, x.toString());
            if (r instanceof String str) emitRegex(ctx, input, value, str);
            else if (r instanceof List<?> l) for (var x : l) emitRegex(ctx, input, value, x.toString());
        }
    }

    private static void emitString(ParseContext ctx, String input, String value, String needle) {
        var hay = input.toLowerCase(Locale.ROOT);
        var n = needle.toLowerCase(Locale.ROOT);
        var validator = Validators.sepsSurround(input);
        int from = 0;
        while (true) {
            int i = hay.indexOf(n, from);
            if (i < 0) break;
            int e = i + n.length();
            var m = new Match("streaming_service", value, i, e, input.substring(i, e),
                1000, Set.of("source-prefix"), false);
            if (validator.test(m)) ctx.matches.add(m);
            from = i + 1;
        }
    }

    private static void emitRegex(ParseContext ctx, String input, String value, String src) {
        Pattern p;
        try { p = Pattern.compile(Abbreviations.dash(src), Pattern.CASE_INSENSITIVE); }
        catch (Exception ex) { return; }
        var validator = Validators.sepsSurround(input);
        var matcher = p.matcher(input);
        while (matcher.find()) {
            int s = matcher.start();
            int e = matcher.end();
            var m = new Match("streaming_service", value, s, e, input.substring(s, e),
                1000, Set.of("source-prefix"), false);
            if (validator.test(m)) ctx.matches.add(m);
        }
    }

    /** Replicates Python ValidateStreamingService — service must abut a source-tagged neighbor. */
    @Override
    public void postProcess(ParseContext ctx) {
        var services = ctx.matches.named("streaming_service").toList();
        if (services.isEmpty()) return;
        var toRemove = new ArrayList<Match>();
        for (var s : services) {
            boolean hasSource = ctx.matches.named("source")
                .anyMatch(m -> Math.abs(m.start() - s.end()) <= 1 || Math.abs(s.start() - m.end()) <= 1);
            if (!hasSource) toRemove.add(s);
        }
        for (var m : toRemove) ctx.matches.remove(m);
    }
}
