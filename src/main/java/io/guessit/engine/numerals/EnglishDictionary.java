package io.guessit.engine.numerals;

import java.util.List;

final class EnglishDictionary implements LanguageDictionary {
    @Override
    public List<String> getWords() {
        return List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty");
    }
}
