package io.guessit.engine.numerals;

import java.util.List;

final class FrenchAltDictionary implements LanguageDictionary {
    @Override
    public List<String> getWords() {
        return List.of("zero", "une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
                "onze", "douze", "treize", "quatorze", "quinze", "seize", "dixsept", "dixhuit", "dixneuf", "vingt");
    }
}
