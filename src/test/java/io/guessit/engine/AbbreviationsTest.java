package io.guessit.engine;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class AbbreviationsTest {
    @Test void dashExpandsToSepClass() {
        var src = Abbreviations.dash("H-264");
        var p = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher("H264").find());
        assertTrue(p.matcher("H-264").find());
        assertTrue(p.matcher("H.264").find());
        assertTrue(p.matcher("H_264").find());
        assertTrue(p.matcher("h 264").find());
    }
    @Test void dashLeavesEscapedDashAlone() {
        // Already escaped \\- should stay literal hyphen.
        var src = Abbreviations.dash("ABC\\-DEF");
        var p = Pattern.compile(src);
        assertTrue(p.matcher("ABC-DEF").find());
        assertFalse(p.matcher("ABC.DEF").find());
    }
    @Test void dashLeavesDashInsideCharClassAlone() {
        var src = Abbreviations.dash("[a-z]+");
        assertEquals("[a-z]+", src);
    }
}
