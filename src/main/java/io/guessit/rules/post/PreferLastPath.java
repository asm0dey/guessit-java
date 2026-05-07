package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.Match;
import io.guessit.engine.MatchName;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private static final java.util.Set<MatchName> TITLE_FAMILY =
        java.util.Set.of(MatchName.TITLE, MatchName.ALTERNATIVE_TITLE, MatchName.EPISODE_TITLE);


    @Override
    public void process(ParseContext ctx) {
        var paths = ctx.markers.stream()
            .filter(m -> "path".equals(m.name()))
            .sorted(Comparator.comparingInt(Marker::start))
            .toList();
        if (paths.size() < 2) return;
        var last = paths.getLast();
        var inLastValues = collectLastFilepartValues(ctx, last);
        if (inLastValues.isEmpty()) return;
        var toDrop = ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.end() <= last.start())
            .filter(m -> inLastValues.containsKey(m.name()))
            // Only drop when the inner filepart's same-named match has a
            // DIFFERENT value. When values match, keep the outer match so
            // marker_sorted still credits the outer filepart for that name.
            .filter(m -> !inLastValues.get(m.name()).contains(m.value()))
            .filter(m -> shouldDropTitleFamilyDup(m, inLastValues))
            // Preserve titles that survived preferTitleWithYear.
            .filter(m -> !(m.name() == MatchName.TITLE && m.tags().contains("equivalent-ignore")))
            .filter(m -> shouldDropSeasonEpisodeOuter(m, ctx, last))
            .toList();
        for (var m : toDrop) ctx.matches.remove(m);
    }

    /** Map name -> set of values present in the last filepart. */
    private static Map<MatchName, Set<Object>> collectLastFilepartValues(ParseContext ctx, Marker last) {
        var values = new EnumMap<MatchName, Set<Object>>(MatchName.class);
        ctx.matches.all()
            .filter(m -> !m.isPrivate())
            .filter(m -> m.start() >= last.start() && m.end() <= last.end())
            .forEach(m -> values.computeIfAbsent(m.name(), _ -> new HashSet<>()).add(m.value()));
        return values;
    }

    /** For title-family names: treat case-insensitively-equal values as duplicates
     *  so the outer's titlecase variant survives over the inner's lowercase. */
    private static boolean shouldDropTitleFamilyDup(Match m, Map<MatchName, Set<Object>> inLastValues) {
        if (!TITLE_FAMILY.contains(m.name())) return true;
        if (!(m.value() instanceof String mv)) return true;
        var inLast = inLastValues.get(m.name());
        for (var v : inLast) {
            if (v instanceof String s && s.equalsIgnoreCase(mv)) return false;
        }
        return true;
    }

    /** Preserve outer SxxExx-tagged season/episode when last filepart has only
     *  non-SxxExx matches for that name (palindrome-tail safety). */
    private static boolean shouldDropSeasonEpisodeOuter(Match m, ParseContext ctx, Marker last) {
        if (m.name() != MatchName.SEASON && m.name() != MatchName.EPISODE) return true;
        if (!m.tags().contains("SxxExx")) return true;
        return ctx.matches.snapshot().stream()
            .filter(s -> !s.isPrivate())
            .filter(s -> m.name().equals(s.name()))
            .filter(s -> s.start() >= last.start() && s.end() <= last.end())
            .anyMatch(s -> s.tags().contains("SxxExx"));
    }
}
