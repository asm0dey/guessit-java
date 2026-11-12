package io.guessit.engine.numerals;

import java.util.List;

final class FrenchDictionary implements LanguageDictionary {
    @Override
    public List<String> getWords() {
        return List.of("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
                "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf", "vingt");
    }
}
