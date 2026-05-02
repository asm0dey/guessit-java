package io.guessit.engine;

import java.util.function.Consumer;

public record OutputPhase(Consumer<ParseContext> assembler) implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        assembler.accept(ctx);
    }
}
