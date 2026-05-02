package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Comparator;
import java.util.HashSet;

public final class PreferLastPath implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        var paths = ctx.markers.stream()
            .filter(m -> "path".equals(m.name()))
            .sorted(Comparator.comparingInt(Marker::start))
            .toList();
        if (paths.size() < 2) return;
        var last = paths.get(paths.size() - 1);
        var inLast = new HashSet<String>();
        ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.start() >= last.start() && m.end() <= last.end())
            .forEach(m -> inLast.add(m.name()));
        if (inLast.isEmpty()) return;
        var toDrop = ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.end() <= last.start())
            .filter(m -> inLast.contains(m.name()))
            .toList();
        for (var m : toDrop) ctx.matches.remove(m);
    }
}
