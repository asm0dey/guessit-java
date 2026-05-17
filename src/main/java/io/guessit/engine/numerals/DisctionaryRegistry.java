package io.guessit.engine.numerals;

import java.util.List;
import java.util.stream.Stream;

final class DictionaryRegistry {
    private DictionaryRegistry() {}

    static List<List<String>> getAllWords() {
        return Stream.of(
                new EnglishDictionary(),
                new FrenchDictionary(),
                new FrenchAltDictionary()
        ).map(LanguageDictionary::getWords).toList();
    }
}