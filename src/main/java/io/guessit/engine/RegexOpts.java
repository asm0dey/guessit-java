package io.guessit.engine;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Knobs for {@link PatternMatcher#regex}. Built fluently from
 * {@link #defaults()}; every {@code with*} call returns a fresh record.
 *
 * <p>{@code valueExtractor} runs first on the raw substring (typically the
 * named {@code value} group) to produce a typed value. {@code valueFormatter}
 * then maps that typed value (e.g. canonicalisation). {@code validator} sees
 * the fully built {@link Match} and may reject it; useful for cross-checking
 * the value against context (e.g. separator surroundings, numeric range).
 */
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
        if (validator == null) validator = _ -> true;
    }
    public static RegexOpts defaults() {
        return new RegexOpts(1000, Set.of(), false, s -> s, v -> v, _ -> true);
    }
    public RegexOpts withPriority(int p) { return new RegexOpts(p, tags, isPrivate, valueExtractor, valueFormatter, validator); }
    public RegexOpts withTags(Set<String> t) { return new RegexOpts(priority, t, isPrivate, valueExtractor, valueFormatter, validator); }
    public RegexOpts withValue(Function<String, Object> ex) { return new RegexOpts(priority, tags, isPrivate, ex, valueFormatter, validator); }
    public RegexOpts withValidator(Predicate<Match> v) { return new RegexOpts(priority, tags, isPrivate, valueExtractor, valueFormatter, v); }
}
