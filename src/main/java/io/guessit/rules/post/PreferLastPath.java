package io.guessit.rules.post;

import io.guessit.engine.Marker;
import io.guessit.engine.MatchName;
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
        // Collect the set of property names actually present in the last
        // filepart. Only those names are eligible for parent-segment pruning;
        // everything else (e.g. a year only present in a parent dir) stays.
        // Map name -> set of values present in the last filepart.
        var inLastValues = new java.util.HashMap<MatchName, java.util.Set<Object>>();
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
            // For title-family names, treat case-insensitively-equal values
            // as duplicates so the outer's titlecase variant survives when
            // the filename has the same string in lowercase. Without this,
            // outer's "Storming Mussolinis Island" gets dropped in favor of
            // the inner filepart's lowercase, and EquivalentHoles can't
            // upgrade because the better-cased value is already gone.
            .filter(m -> {
                if (!TITLE_FAMILY.contains(m.name())) return true;
                if (!(m.value() instanceof String mv)) return true;
                var inLast = inLastValues.get(m.name());
                for (var v : inLast) {
                    if (v instanceof String s && s.equalsIgnoreCase(mv)) return false;
                }
                return true;
            })
            // Preserve titles that survived preferTitleWithYear: they were
            // picked because their filepart has a year-in-group. Dropping
            // them lets the inner filepart's title (often poorer casing or
            // has a release-group prefix like "blow-…") win.
            .filter(m -> !(m.name() == MatchName.TITLE && m.tags().contains("equivalent-ignore")))
            // Preserve outer SxxExx-tagged season/episode when the last
            // filepart only has non-SxxExx matches for that name. Palindrome-
            // tail filenames produce bogus weak-episode/weak-duplicate matches
            // in the inner filepart (e.g. "5102.sregnesseM..." → season=51);
            // they shouldn't displace the real outer S01E07.
            // RemoveLessSpecificSeasonEpisode handles the proper marker_sorted
            // comparison later.
            .filter(m -> {
                if (m.name() != MatchName.SEASON && m.name() != MatchName.EPISODE) return true;
                if (!m.tags().contains("SxxExx")) return true;
                return ctx.matches.snapshot().stream()
                    .filter(s -> !s.isPrivate())
                    .filter(s -> m.name().equals(s.name()))
                    .filter(s -> s.start() >= last.start() && s.end() <= last.end())
                    .anyMatch(s -> s.tags().contains("SxxExx"));
            })
            .toList();
        for (var m : toDrop) ctx.matches.remove(m);
    }
}
