package io.guessit;

import io.guessit.config.ConfigLoader;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.ParseContext;
import io.guessit.engine.Pipeline;
import io.guessit.engine.Trace;
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
        return guess(input, Trace.NOOP);
    }

    public GuessResult guess(String input, Trace trace) {
        var ctx = new ParseContext(input, options, config, trace);
        trace.input(input);
        pipeline.run(ctx);
        trace.result(ctx.result);
        return ctx.result;
    }

}
