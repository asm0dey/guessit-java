package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.LinkedHashMap;

/**
 * Emits {@code proper_count} = total weight of distinct {@code other='Proper'} matches.
 * Each Proper raw counts +1, or +2 when tagged {@code real} (Real / Real-Proper /
 * Real-Repack / Real-Rerip). Mirrors python {@code other.py:ProperCountRule}.
 */
public final class ProperCountRule implements PostProcessor {

    @Override
    public void process(ParseContext ctx) {
        var distinct = new LinkedHashMap<String, Match>();
        ctx.matches.named("other")
            .filter(m -> "Proper".equals(m.value()))
            .forEach(m -> distinct.putIfAbsent(rawCleanup(m.raw()), m));
        if (distinct.isEmpty()) return;

        int total = 0;
        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        for (var m : distinct.values()) {
            total += m.tags().contains("real") ? 2 : 1;
            if (m.start() < start) start = m.start();
            if (m.end() > end) end = m.end();
        }
        ctx.matches.add(new Match("proper_count", total, start, end,
            ctx.input.substring(start, end), 1000, java.util.Set.of(), false));
    }

    private static String rawCleanup(String raw) {
        return raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[\\W_]+", "");
    }
}
