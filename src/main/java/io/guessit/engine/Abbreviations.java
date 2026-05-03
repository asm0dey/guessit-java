package io.guessit.engine;

public final class Abbreviations {
    private Abbreviations() {}

    /** Python `seps_no_fs` (seps with '/' and '\\' removed) escaped for a regex char class. */
    public static final String SEPS_NO_FS_CLASS = sepsNoFsClass();

    private static String sepsNoFsClass() {
        var sb = new StringBuilder();
        for (char c : Seps.CHARS.toCharArray()) {
            if (c == '/' || c == '\\') continue;
            if (c == ']' || c == '^' || c == '-' || c == '[') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** Replace every unescaped, non-class `-` in the source with `[<seps_no_fs>]`.
     *  Mirrors Python rebulk's dash abbreviation: a single separator character (not zero-or-more). */
    public static String dash(String src) {
        return rewriteLiteral(src, '-', "[" + SEPS_NO_FS_CLASS + "]");
    }

    /** Replace every unescaped, non-class `@` in the source with `[<seps_no_fs>]`. */
    public static String altDash(String src) {
        return rewriteLiteral(src, '@', "[" + SEPS_NO_FS_CLASS + "]");
    }

    private static String rewriteLiteral(String src, char target, String replacement) {
        var sb = new StringBuilder(src.length() + 16);
        boolean escaped = false;
        int classDepth = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (escaped) { sb.append(c); escaped = false; continue; }
            if (c == '\\') { sb.append(c); escaped = true; continue; }
            if (c == '[') { classDepth++; sb.append(c); continue; }
            if (c == ']' && classDepth > 0) { classDepth--; sb.append(c); continue; }
            if (c == target && classDepth == 0) { sb.append(replacement); continue; }
            sb.append(c);
        }
        return sb.toString();
    }
}
