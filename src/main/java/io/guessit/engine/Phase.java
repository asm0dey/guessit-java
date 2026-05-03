package io.guessit.engine;

/**
 * One stage of the parsing {@link Pipeline}. Sealed so the set of phase kinds
 * is fixed and reviewable in one place; new behaviour is added by registering
 * additional producers/extractors/processors inside an existing phase rather
 * than by adding new phase types.
 *
 * <p>Phase order, defined by {@code io.guessit.rules.Rules.defaultPipeline()}:
 * <ol>
 *   <li>{@link MarkerPhase} — produce path / group markers</li>
 *   <li>{@link ExtractorPhase} — extractors add candidate matches</li>
 *   <li>{@link ConflictPhase} — drop overlapping losers</li>
 *   <li>{@link ExtractorPostPhase} — extractors refine survivors</li>
 *   <li>{@link PostPhase} — cross-cutting cleanup</li>
 *   <li>{@link OutputPhase} — assemble the result</li>
 * </ol>
 */
public sealed interface Phase permits MarkerPhase, ExtractorPhase, ConflictPhase, ExtractorPostPhase, PostPhase, OutputPhase {
    /** Applies this phase's effects to the shared {@link ParseContext}. */
    void apply(ParseContext ctx);
}
