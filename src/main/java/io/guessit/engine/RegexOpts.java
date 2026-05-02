package io.guessit.engine;

import java.util.Set;
import java.util.function.Function;

public record RegexOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    Function<String, Object> valueExtractor,
    Function<Object, Object> valueFormatter
) {
    public RegexOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
    public static RegexOpts defaults() {
        return new RegexOpts(1000, Set.of(), false, s -> s, v -> v);
    }
    public RegexOpts withPriority(int p) { return new RegexOpts(p, tags, isPrivate, valueExtractor, valueFormatter); }
    public RegexOpts withTags(Set<String> t) { return new RegexOpts(priority, t, isPrivate, valueExtractor, valueFormatter); }
    public RegexOpts asPrivate() { return new RegexOpts(priority, tags, true, valueExtractor, valueFormatter); }
    public RegexOpts withValue(Function<String, Object> ex) { return new RegexOpts(priority, tags, isPrivate, ex, valueFormatter); }
    public RegexOpts withFormatter(Function<Object, Object> fmt) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, fmt); }
}
