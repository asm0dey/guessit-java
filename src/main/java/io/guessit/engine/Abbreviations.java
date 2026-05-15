package io.guessit.engine;

import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;

import java.util.ArrayList;
import java.util.List;

import static com.mirkoddd.sift.core.SiftPatterns.anyOf;
import static com.mirkoddd.sift.core.SiftPatterns.literal;

/**
 * Mirrors Python rebulk's pattern-source rewriting helpers.
 *
 * <p>guessit defines many regexes using a shorthand where a literal {@code -}
 * means "any single non-FS separator". {@link #dash} expands that shorthand
 * into a proper character class, so the same pattern matches {@code "WEB-DL"},
 * {@code "WEB.DL"}, {@code "WEB DL"}, etc. The rewrite is character-aware —
 * dashes inside {@code [...]} character classes or after an escape are left
 * alone.
 */
public final class Abbreviations {
    private Abbreviations() {}

    /** Python `seps_no_fs` (seps with '/' and '\\' removed) escaped for a regex char class. */
    public static final SiftPattern<Fragment> SEPS_NO_FS_PATTERN = buildSepsPattern();

    private static SiftPattern<Fragment> buildSepsPattern() {
        List<SiftPattern<Fragment>> chars = new ArrayList<>();
        for (char c : Seps.CHARS.toCharArray()) {
            if (c != '/' && c != '\\') {
                chars.add(literal(String.valueOf(c)));
            }
        }
        return anyOf(chars);
    }

    /** Replace every unescaped, non-class `-` in the source with `[<seps_no_fs>]`.
     *  Mirrors Python rebulk's dash abbreviation: a single separator character (not zero-or-more). */
    public static String dash(String src) {
        return rewriteLiteral(src, SEPS_NO_FS_PATTERN.shake());
    }

    private static String rewriteLiteral(String src, String replacement) {
        var sb = new StringBuilder(src.length() + 16);
        boolean escaped = false;
        int classDepth = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (escaped) { sb.append(c); escaped = false; continue; }
            if (c == '\\') { sb.append(c); escaped = true; continue; }
            if (c == '[') { classDepth++; sb.append(c); continue; }
            if (c == ']' && classDepth > 0) { classDepth--; sb.append(c); continue; }
            if (c == '-' && classDepth == 0) { sb.append(replacement); continue; }
            sb.append(c);
        }
        return sb.toString();
    }
}
