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
    public Trace trace = Trace.NOOP;
    public GuessResultBuilder resultBuilder = GuessResultBuilder.result();
    /** Final assembled result; written by {@link OutputPhase}. */
    public GuessResult result;
    /**
     * Counter for coupling groups. A rule that produces multiple sibling
     * matches sharing one regex pass tags each with {@code "cg:" + nextCoexistGroupId()}
     * so {@link io.guessit.rules.post.OutputBuilder} can drop the whole pair
     * when any sibling is filtered by includes/excludes.
     */
    private long coexistGroupCounter = 0;
    public String nextCoexistGroupTag() {
        return "cg:" + (++coexistGroupCounter);
    }

    public ParseContext(String input, Options options, OptionsConfig config) {
        this.input = input;
        this.options = options;
        this.config = config;
    }
}
