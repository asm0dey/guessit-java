package io.guessit.engine;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public record RegexOpts(
    int priority,
    Set<String> tags,
    boolean isPrivate,
    Function<String, Object> valueExtractor,
    Function<Object, Object> valueFormatter,
    Predicate<Match> validator
) {
    public RegexOpts {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (validator == null) validator = m -> true;
    }
    public static RegexOpts defaults() {
        return new RegexOpts(1000, Set.of(), false, s -> s, v -> v, m -> true);
    }
    public RegexOpts withPriority(int p) { return new RegexOpts(p, tags, isPrivate, valueExtractor, valueFormatter, validator); }
    public RegexOpts withTags(Set<String> t) { return new RegexOpts(priority, t, isPrivate, valueExtractor, valueFormatter, validator); }
    public RegexOpts asPrivate() { return new RegexOpts(priority, tags, true, valueExtractor, valueFormatter, validator); }
    public RegexOpts withValue(Function<String, Object> ex) { return new RegexOpts(priority, tags, isPrivate, ex, valueFormatter, validator); }
    public RegexOpts withFormatter(Function<Object, Object> fmt) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, fmt, validator); }
    public RegexOpts withValidator(Predicate<Match> v) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, valueFormatter, v); }
}
