package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static io.guessit.engine.Formatters.*;
import static org.assertj.core.api.Assertions.assertThat;

class FormattersTest {
    @Test void cleanupReplacesSepsWithSpacesAndCollapses() {
        assertThat(cleanup("Movie.Name")).isEqualTo("Movie Name");
        assertThat(cleanup("  Movie___Name  ")).isEqualTo("Movie Name");
        assertThat(cleanup("a..b..c")).isEqualTo("a b c");
    }
    @Test void cleanupKeepsCommasColonsDashesSlashes() {
        // Python excludes ,:;-/\ from the replacement set, so they survive cleanup.
        assertThat(cleanup("a,b")).isEqualTo("a,b");
        assertThat(cleanup("a-b")).isEqualTo("a-b");
        assertThat(cleanup("a:b")).isEqualTo("a:b");
    }
    @Test void cleanupRestoresSingleCharDottedRuns() {
        // S.H.I.E.L.D. survives because each dot separates single chars on both sides.
        assertThat(cleanup("Marvels.Agents.of.S.H.I.E.L.D")).isEqualTo("Marvels Agents of S.H.I.E.L.D");
    }
    @Test void reorderTitlePromotesArticle() {
        assertThat(reorderTitle("Matrix, The")).isEqualTo("The Matrix");
        assertThat(reorderTitle("Matrix,The")).isEqualTo("The Matrix");
    }
    @Test void reorderTitleNoOpWhenNoArticle() {
        assertThat(reorderTitle("The Matrix")).isEqualTo("The Matrix");
        assertThat(reorderTitle("Foo Bar")).isEqualTo("Foo Bar");
    }
    @Test void stripRemovesSepsFromBothEnds() {
        assertThat(strip(".. foo --")).isEqualTo("foo");
    }
    @Test void titleTextChainsCleanupAndReorder() {
        // .. collapses to space, "Matrix The" has no comma so reorder is no-op.
        assertThat(titleText("Matrix..The")).isEqualTo("Matrix The");
    }
}
