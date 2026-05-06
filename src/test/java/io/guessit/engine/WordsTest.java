package io.guessit.engine;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.guessit.engine.Words.Word;
import static io.guessit.engine.Words.iter;
import static org.assertj.core.api.Assertions.assertThat;

class WordsTest {

    @Test
    void testEmptyInputReturnsEmptyList() {
        Assertions.assertThat(Words.iter("")).isEmpty();
    }

    @Test
    void testOnlySeparatorsReturnsEmptyList() {
        Assertions.assertThat(Words.iter(".- _/+")).isEmpty();
    }

    @Test
    void testSingleWord() {
        var words = iter("hello");
        Assertions.assertThat(words).hasSize(1);
        assertThat(words.getFirst()).isEqualTo(new Word(0, 5, "hello"));
    }

    @Test
    void testWordsSeparatedByNonWordChars() {
        var words = iter("Show.S01E02.Pilot");
        Assertions.assertThat(words).hasSize(3);
        assertThat(words.get(0)).isEqualTo(new Word(0, 4, "Show"));
        assertThat(words.get(1)).isEqualTo(new Word(5, 11, "S01E02"));
        assertThat(words.get(2)).isEqualTo(new Word(12, 17, "Pilot"));
    }

    @Test
    void testNumberSeparatorWord() {
        var words = iter("hello-123-world");
        Assertions.assertThat(words).hasSize(3);
        assertThat(words.get(0)).isEqualTo(new Word(0, 5, "hello"));
        assertThat(words.get(1)).isEqualTo(new Word(6, 9, "123"));
        assertThat(words.get(2)).isEqualTo(new Word(10, 15, "world"));
    }

    @Test
    void testConsecutiveSeparatorsAreSkipped() {
        var words = iter("word1...word2..word3");
        Assertions.assertThat(words).hasSize(3);
        assertThat(words.get(0)).isEqualTo(new Word(0, 5, "word1"));
        assertThat(words.get(1)).isEqualTo(new Word(8, 13, "word2"));
        assertThat(words.get(2)).isEqualTo(new Word(15, 20, "word3"));
    }
}
