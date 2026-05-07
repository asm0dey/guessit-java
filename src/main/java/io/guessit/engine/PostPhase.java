package io.guessit.engine;

import java.util.List;

/**
 * Phase 5 — cross-cutting cleanup that doesn't belong to any single extractor.
 *
 * <p>Default processors include {@code PreferLastPath} (drop matches in
 * earlier path segments when the last segment already produced one of the
 * same name), {@code PrivateRemover} (drop scaffolding matches), and
 * {@code TitleMarkerSelector} (pick the path segment from which the title
 * will be derived).
 */
public record PostPhase(List<PostProcessor> processors) implements Phase {
    /** Stateless callback over the final, deconflicted match set. */
    @FunctionalInterface
    public interface PostProcessor extends Described {
        void process(ParseContext ctx);

        @Override
        default String description() { return getClass().getSimpleName(); }
    }

    public PostPhase { processors = List.copyOf(processors); }

    @Override
    public void apply(ParseContext ctx) {
        ctx.trace.phase("post", "running heuristic rules");
        for (var p : processors) {
            var before = ctx.matches.snapshot();
            ctx.trace.step("rule", p.getClass().getSimpleName(), p.description());
            p.process(ctx);
            TraceDiff.emit(before, ctx.matches.snapshot(), ctx);
        }
    }
}
