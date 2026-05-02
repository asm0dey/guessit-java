package io.guessit.engine;

import java.util.Set;

public record StringOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    boolean caseSensitive,
    boolean wholeWord
) {
    public StringOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
    public static StringOpts defaults() {
        return new StringOpts(1000, Set.of(), false, false, true);
    }
    public StringOpts withPriority(int p) { return new StringOpts(p, tags, isPrivate, caseSensitive, wholeWord); }
    public StringOpts withTags(Set<String> t) { return new StringOpts(priority, t, isPrivate, caseSensitive, wholeWord); }
    public StringOpts caseSensitive(boolean v) { return new StringOpts(priority, tags, isPrivate, v, wholeWord); }
    public StringOpts wholeWord(boolean v) { return new StringOpts(priority, tags, isPrivate, caseSensitive, v); }
}
