package io.guessit.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordsTest {

    @Test
    void testEmptyInputReturnsEmptyList() {
        assertTrue(Words.iter("").isEmpty());
    }

    @Test
    void testOnlySeparatorsReturnsEmptyList() {
        assertTrue(Words.iter(".- _/+").isEmpty());
    }

    @Test
    void testSingleWord() {
        var words = Words.iter("hello");
        assertEquals(1, words.size());
        assertEquals(new Words.Word(0, 5, "hello"), words.getFirst());
    }

    @Test
    void testWordsSeparatedByNonWordChars() {
        var words = Words.iter("Show.S01E02.Pilot");
        assertEquals(3, words.size());
        assertEquals(new Words.Word(0, 4, "Show"), words.get(0));
        assertEquals(new Words.Word(5, 11, "S01E02"), words.get(1));
        assertEquals(new Words.Word(12, 17, "Pilot"), words.get(2));
    }

    @Test
    void testNumberSeparatorWord() {
        var words = Words.iter("hello-123-world");
        assertEquals(3, words.size());
        assertEquals(new Words.Word(0, 5, "hello"), words.get(0));
        assertEquals(new Words.Word(6, 9, "123"), words.get(1));
        assertEquals(new Words.Word(10, 15, "world"), words.get(2));
    }

    @Test
    void testConsecutiveSeparatorsAreSkipped() {
        var words = Words.iter("word1...word2..word3");
        assertEquals(3, words.size());
        assertEquals(new Words.Word(0, 5, "word1"), words.get(0));
        assertEquals(new Words.Word(8, 13, "word2"), words.get(1));
        assertEquals(new Words.Word(15, 20, "word3"), words.get(2));
    }
}
