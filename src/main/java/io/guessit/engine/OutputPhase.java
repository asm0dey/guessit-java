package io.guessit.engine;

import java.util.function.Consumer;

/**
 * Phase 6 — assemble {@link ParseContext#result} from the surviving matches.
 *
 * <p>The default assembler ({@code OutputBuilder}) groups matches by name,
 * coerces values to typed {@code GuessResult} fields (string, int, list,
 * {@code Language}, {@code Country}, {@code Quantity}, {@code LocalDate}),
 * and routes anything unrecognised into the {@code extras} map.
 *
 * <p>Keeping this in its own phase isolates field-shape decisions (scalar
 * vs list, type coercion) from the extractors that produced the values.
 */
public record OutputPhase(Consumer<ParseContext> assembler) implements Phase {
    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("output", "assembling result");
        assembler.accept(ctx);
    }
}
