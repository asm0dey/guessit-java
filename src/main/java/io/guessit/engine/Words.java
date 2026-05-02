package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;

public final class Words {
    private Words() {}

    public record Word(int start, int end, String value) {}

    public static List<Word> iter(String input) {
        var out = new ArrayList<Word>();
        int n = input.length();
        int i = 0;
        while (i < n) {
            if (isWordChar(input.charAt(i))) {
                int s = i;
                while (i < n && isWordChar(input.charAt(i))) i++;
                out.add(new Word(s, i, input.substring(s, i)));
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
