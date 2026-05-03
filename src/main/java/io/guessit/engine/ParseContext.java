package io.guessit.engine;

import io.guessit.GuessResult;
import io.guessit.GuessResultBuilder;
import io.guessit.Options;
import io.guessit.config.OptionsConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-parse state threaded through every {@link Phase}.
 *
 * <p>Fields are public for direct access from extractors and processors. The
 * lifecycle:
 * <ul>
 *   <li>Constructor receives immutable inputs ({@code input}, {@code options},
 *       {@code config}). {@link #matches} and {@link #markers} start empty.</li>
 *   <li>{@link MarkerPhase} populates {@link #markers}.</li>
 *   <li>{@link ExtractorPhase} and {@link ExtractorPostPhase} mutate
 *       {@link #matches}.</li>
 *   <li>{@link PostPhase} processors finish cleaning {@link #matches}.</li>
 *   <li>{@link OutputPhase} writes the assembled value to {@link #result}.</li>
 * </ul>
 */
public final class ParseContext {
    public final String input;
    public final Options options;
    public final OptionsConfig config;
    public final MatchSet matches = new MatchSet();
    public final List<Marker> markers = new ArrayList<>();
    public GuessResultBuilder resultBuilder = GuessResult.builder();
    /** Final assembled result; written by {@link OutputPhase}. */
    public GuessResult result;

    public ParseContext(String input, Options options, OptionsConfig config) {
        this.input = input;
        this.options = options;
        this.config = config;
    }
}
