package io.guessit.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an input into maximal runs of letters/digits with their original spans.
 *
 * <p>Used by extractors that need to reason about token shape rather than
 * regex matches — release-group detection, weak-episode candidates, and
 * similar rules that score adjacency between non-separator tokens.
 */
public final class Words {
    private Words() {}

    /** A single word span with its substring value. */
    public record Word(int start, int end, String value) {}

    /** Iterates words; separators (punctuation/whitespace) are skipped, never reported. */
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
