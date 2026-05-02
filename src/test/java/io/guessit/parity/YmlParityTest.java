package io.guessit.parity;

import io.guessit.Guessit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YmlParityTest {

    @Disabled("phase-0: no rules yet; enable per phase as rules ship")
    @ParameterizedTest(name = "{0}")
    @MethodSource("allYmlCases")
    void ymlParity(YmlCase c) {
        var result = Guessit.parse(c.input(), c.options()).toMap();
        if (c.negative()) {
            assertNotEquals(c.expected(), result, "negative case unexpectedly matched");
        } else {
            assertEquals(c.expected(), result);
        }
    }

    static Stream<YmlCase> allYmlCases() {
        return YmlTestLoader.discoverAll("yml/");
    }
}
