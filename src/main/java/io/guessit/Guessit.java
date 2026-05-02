package io.guessit;

import io.guessit.config.ConfigLoader;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Pipeline;
import io.guessit.rules.Rules;

public final class Guessit {

    private final Options options;
    private final OptionsConfig config;
    private final Pipeline pipeline;

    private Guessit(Options options) {
        this.options = options;
        this.config = ConfigLoader.load(options);
        this.pipeline = new Pipeline(Rules.defaultPipeline());
    }

    public static Guessit withOptions(Options options) { return new Guessit(options); }

    public static GuessResult parse(String input) { return parse(input, Options.defaults()); }

    public static GuessResult parse(String input, Options options) {
        return withOptions(options).guess(input);
    }

    public GuessResult guess(String input) {
        var ctx = new ParseContext(input, options, config);
        pipeline.run(ctx);
        return ctx.result;
    }

    public java.util.Map<String, java.util.List<Object>> properties() {
        // Stub: no extractors yet. Real implementation lands with rule introspection.
        return java.util.Map.of();
    }

    public java.util.List<String> suggestedExpected(java.util.Collection<String> titles) {
        // Stub: returns unique titles unchanged. Real heuristic lands when title rule does (Plan 4).
        return titles.stream().distinct().toList();
    }
}
