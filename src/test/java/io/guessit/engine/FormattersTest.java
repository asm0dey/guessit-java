package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormattersTest {
    @Test void cleanupReplacesSepsWithSpacesAndCollapses() {
        assertEquals("Movie Name", Formatters.cleanup("Movie.Name"));
        assertEquals("Movie Name", Formatters.cleanup("  Movie___Name  "));
        assertEquals("a b c", Formatters.cleanup("a..b..c"));
    }
    @Test void cleanupKeepsCommasColonsDashesSlashes() {
        // Python excludes ,:;-/\ from the replacement set, so they survive cleanup.
        assertEquals("a,b", Formatters.cleanup("a,b"));
        assertEquals("a-b", Formatters.cleanup("a-b"));
        assertEquals("a:b", Formatters.cleanup("a:b"));
    }
    @Test void cleanupRestoresSingleCharDottedRuns() {
        // S.H.I.E.L.D. survives because each dot separates single chars on both sides.
        assertEquals("Marvels Agents of S.H.I.E.L.D",
            Formatters.cleanup("Marvels.Agents.of.S.H.I.E.L.D"));
    }
    @Test void reorderTitlePromotesArticle() {
        assertEquals("The Matrix", Formatters.reorderTitle("Matrix, The"));
        assertEquals("The Matrix", Formatters.reorderTitle("Matrix,The"));
    }
    @Test void reorderTitleNoOpWhenNoArticle() {
        assertEquals("The Matrix", Formatters.reorderTitle("The Matrix"));
        assertEquals("Foo Bar", Formatters.reorderTitle("Foo Bar"));
    }
    @Test void stripRemovesSepsFromBothEnds() {
        assertEquals("foo", Formatters.strip(".. foo --"));
    }
    @Test void titleTextChainsCleanupAndReorder() {
        // .. collapses to space, "Matrix The" has no comma so reorder is no-op.
        assertEquals("Matrix The", Formatters.titleText("Matrix..The"));
    }
}
