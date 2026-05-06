package io.guessit.engine;

import java.util.Set;

/**
 * One extracted property occurrence.
 *
 * <p>Carries both the parsed value (an {@code Integer} for {@code year}, a
 * {@code Language} for {@code language}, and so on) and the raw substring
 * span it came from. The span is half-open: {@code [start, end)}.
 *
 * <p>Two metadata channels influence later phases:
 * <ul>
 *   <li>{@code priority} — tie-breaker in {@link ConflictPhase} when two
 *       overlapping matches have equal length; higher wins.</li>
 *   <li>{@code tags} — string flags read by other rules. Notable values:
 *       {@code "coexist"} (opt out of conflict resolution; allowed to overlap),
 *       {@code "SxxExx"} (set by {@code SeasonEpisodeExtractor}; gates
 *       {@code WeakEpisodeExtractor}'s trailing-weak → {@code absolute_episode}
 *       rename pass).</li>
 *   <li>{@code isPrivate} — match exists only to influence other rules and
 *       is dropped before output by the {@code PrivateRemover} processor.</li>
 * </ul>
 */
public record Match(
    MatchName name,
    Object value,
    int start,
    int end,
    String raw,
    int priority,
    Set<String> tags,
    boolean isPrivate
) {
    public Match {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    /** Convenience factory: default priority {@code 1000}, no tags, public. */
    public static Match of(MatchName name, Object value, int start, int end, String raw) {
        return new Match(name, value, start, end, raw, 1000, Set.of(), false);
    }

    public Match withPriority(int p) {
        return new Match(name, value, start, end, raw, p, tags, isPrivate);
    }

    public Match withTags(Set<String> t) {
        return new Match(name, value, start, end, raw, priority, t, isPrivate);
    }

    public Match withStart(int s) {
        return new Match(name, value, s, end, raw, priority, tags, isPrivate);
    }

    public Match withEnd(int e) {
        return new Match(name, value, start, e, raw, priority, tags, isPrivate);
    }

    public Match withName(MatchName n) {
        return new Match(n, value, start, end, raw, priority, tags, isPrivate);
    }

    public int length() { return end - start; }

    /** True if the spans of this match and {@code other} share at least one position. */
    public boolean overlaps(Match other) {
        return this.start < other.end && other.start < this.end;
    }
}
