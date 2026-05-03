package io.guessit.rules.property;

import io.guessit.Options;
import io.guessit.config.OptionsConfig;
import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.rules.markers.GroupMarker;
import io.guessit.rules.markers.PathMarker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YearExtractorTest {

    private static List<Match> run(String input) {
        var ctx = new ParseContext(input, Options.defaults(), OptionsConfig.empty());
        new PathMarker().produce(ctx);
        new GroupMarker().produce(ctx);
        var x = new YearExtractor();
        x.extract(ctx);
        x.postProcess(ctx);
        return ctx.matches.named("year").toList();
    }

    @Test void simpleYear() {
        var ms = run("Movie.Title.2015.1080p.mkv");
        assertEquals(1, ms.size());
        assertEquals(2015, ms.get(0).value());
    }
    @Test void rejectsOutOfRange() {
        assertTrue(run("file.1900.mkv").isEmpty());
        assertTrue(run("file.2030.mkv").isEmpty());
        assertTrue(run("file.1234.mkv").isEmpty());
    }
    @Test void requiresSepsSurround() {
        assertTrue(run("X2015Y").isEmpty());
    }
    @Test void prefersGroupedYearWhenMultipleInFilepart() {
        // Marked year wins, ungrouped years dropped after the first.
        var ms = run("Movie.2015.Title.[2018].mkv");
        assertEquals(1, ms.size());
        assertEquals(2018, ms.get(0).value());
    }
    @Test void noGroupedYear_keepsFirstAndDropsLaterDuplicates() {
        var ms = run("Movie.2015.Cut.2018.Edit.2020.mkv");
        // Ungrouped: keep first, keep nothing past index 1; per Python: keep [0], drop [2..]
        assertEquals(2, ms.size());
        assertEquals(2015, ms.get(0).value());
        assertEquals(2018, ms.get(1).value());
    }
    @Test void boundaryValues() {
        assertEquals(1920, run("F.1920.mkv").get(0).value());
        assertEquals(2029, run("F.2029.mkv").get(0).value());
    }
}
