package io.guessit.engine.numerals;

import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.SiftPatterns;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.mirkoddd.sift.core.Sift.oneOrMore;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;

/**
 * Handles validation and parsing of localized number words.
 */
final class WordNumerals implements TokenNumeralParser {

    WordNumerals() {
    }

    private static final Map<String, Integer> WORD_VALUES = buildWordMap();

    static final SiftPattern<Fragment> PATTERN = buildPattern();

    private static Map<String, Integer> buildWordMap() {
        Map<String, Integer> map = new HashMap<>();
        for (List<String> dict : DictionaryRegistry.getAllWords()) {
            for (int i = 0; i < dict.size(); i++) {
                map.putIfAbsent(dict.get(i).toLowerCase(Locale.ROOT), i);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static SiftPattern<Fragment> buildPattern() {
        var wordLiterals = WORD_VALUES.keySet().stream()
                .map(SiftPatterns::literal)
                .toList();

        return Sift.fromAnywhere()
                .mustBeFollowedBy(oneOrMore().wordCharacters())
                .then().of(anyOf(wordLiterals));
    }

    @Override
    public Integer tryParse(List<String> words) {
        return words.stream()
                .map(word -> WORD_VALUES.get(word.toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}