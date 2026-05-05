package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Comparator;
import java.util.HashSet;

/**
 * When multiple path segments produced matches of the same name, drop the
 * matches in earlier (parent-directory) segments and keep only those in the
 * last segment.
 *
 * <p>Rationale: parent directories often contain hints (year, source, codec)
 * that are also encoded in the filename. The filename is the authoritative
 * source for the work itself, so let it win whenever it spoke up.
 */
public final class PreferLastPath implements PostProcessor {
    @Override
    public void process(ParseContext ctx) {
        var paths = ctx.markers.stream()
            .filter(m -> "path".equals(m.name()))
            .sorted(Comparator.comparingInt(Marker::start))
            .toList();
        if (paths.size() < 2) return;
        var last = paths.getLast();
        // Collect the set of property names actually present in the last
        // filepart. Only those names are eligible for parent-segment pruning;
        // everything else (e.g. a year only present in a parent dir) stays.
        // Map name -> set of values present in the last filepart.
        var inLastValues = new java.util.HashMap<String, java.util.Set<Object>>();
        ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.start() >= last.start() && m.end() <= last.end())
            .forEach(m -> inLastValues.computeIfAbsent(m.name(), _ -> new HashSet<>()).add(m.value()));
        if (inLastValues.isEmpty()) return;
        var toDrop = ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.end() <= last.start())
            .filter(m -> inLastValues.containsKey(m.name()))
            // Only drop when the inner filepart's same-named match has a
            // DIFFERENT value. When values match, keep the outer match so
            // marker_sorted still credits the outer filepart for that name
            // (e.g. duplicated year/source/codec) — otherwise outer collapses
            // to its release-group alone and RemoveAmbiguous later picks the
            // inner filepart's lower-cased title (e.g. "blow-how to be single"
            // for ".../How.To.Be.Single...-BLOW/blow-how.to.be.single...mkv").
            .filter(m -> !inLastValues.get(m.name()).contains(m.value()))
            // Preserve titles that survived preferTitleWithYear: they were
            // picked because their filepart has a year-in-group. Dropping
            // them lets the inner filepart's title (often poorer casing or
            // has a release-group prefix like "blow-…") win.
            .filter(m -> !("title".equals(m.name()) && m.tags().contains("equivalent-ignore")))
            .toList();
        for (var m : toDrop) ctx.matches.remove(m);
    }
}
