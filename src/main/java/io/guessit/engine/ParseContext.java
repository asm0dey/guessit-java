package io.guessit.engine;

import io.guessit.GuessResult;
import io.guessit.GuessResultBuilder;
import io.guessit.Options;
import io.guessit.config.OptionsConfig;

import java.util.ArrayList;
import java.util.List;

public final class ParseContext {
    public final String input;
    public final Options options;
    public final OptionsConfig config;
    public final MatchSet matches = new MatchSet();
    public final List<Marker> markers = new ArrayList<>();
    public Marker titleMarker;          // chosen by TitleMarkerSelector
    public GuessResultBuilder resultBuilder = GuessResult.builder();
    public GuessResult result;

    public ParseContext(String input, Options options, OptionsConfig config) {
        this.input = input;
        this.options = options;
        this.config = config;
    }
}
