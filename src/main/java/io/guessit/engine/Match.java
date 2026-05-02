package io.guessit.engine;

import java.util.Set;

public record Match(
    String name,
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

    public static Match of(String name, Object value, int start, int end, String raw) {
        return new Match(name, value, start, end, raw, 1000, Set.of(), false);
    }

    public Match withPriority(int p) {
        return new Match(name, value, start, end, raw, p, tags, isPrivate);
    }

    public Match withTags(Set<String> t) {
        return new Match(name, value, start, end, raw, priority, t, isPrivate);
    }

    public Match asPrivate() {
        return new Match(name, value, start, end, raw, priority, tags, true);
    }

    public int length() { return end - start; }

    public boolean overlaps(Match other) {
        return this.start < other.end && other.start < this.end;
    }
}
