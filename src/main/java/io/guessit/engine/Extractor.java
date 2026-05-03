package io.guessit.engine;

/**
 * Recognises one property in the input filename and adds {@link Match}es for it.
 *
 * <p>Each extractor runs twice during a parse:
 * <ul>
 *   <li>{@link #extract} during {@link ExtractorPhase} — adds raw candidate matches.
 *       All extractors complete this pass before any conflict resolution runs,
 *       so candidates can be compared against every other extractor's output.</li>
 *   <li>{@link #postProcess} during {@link ExtractorPostPhase} — runs after
 *       {@link ConflictPhase} has pruned overlaps, so the extractor sees only
 *       the surviving matches and can rename, drop, or repartition them.</li>
 * </ul>
 *
 * <p>Most properties only need {@code extract}; the second pass exists for
 * rules that depend on what survived conflict resolution (e.g. promoting weak
 * leading numerics to {@code absolute_episode} only when an {@code SxxExx}
 * episode also survived in the same filepart).
 */
public interface Extractor {
    /**
     * Property name used as the {@link Match#name()} for matches this extractor
     * adds. Maps to a {@code GuessResult} field via the output builder, or to
     * the {@code extras} map when no field exists.
     */
    String name();

    /**
     * Tie-breaker for {@link ConflictPhase} when two overlapping matches have
     * equal span length. Higher wins. Default {@code 1000}; lower values mark
     * deliberately weak/heuristic matches that should yield to anything stronger.
     */
    default int priority() { return 1000; }

    /** First pass: scan the input and add candidate matches to {@code ctx.matches}. */
    void extract(ParseContext ctx);

    /**
     * Second pass: refine matches after conflict resolution. The default is a
     * no-op; override only when the extractor needs to act on the deconflicted
     * match set.
     */
    default void postProcess(ParseContext ctx) {}
}
