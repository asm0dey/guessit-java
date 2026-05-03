package io.guessit.engine;

public final class Formatters {
    private Formatters() {}

    private static final String EXCLUDED_CLEAN_CHARS = ",:;-/\\";

    private static final String CLEAN_CHARS;
    static {
        var sb = new StringBuilder();
        for (var c : Seps.CHARS.toCharArray()) {
            if (EXCLUDED_CLEAN_CHARS.indexOf(c) < 0) sb.append(c);
        }
        CLEAN_CHARS = sb.toString();
    }

    public static String strip(String s) {
        if (s == null || s.isEmpty()) return s;
        var start = 0;
        var end = s.length();
        while (start < end && Seps.isSep(s.charAt(start))) start++;
        while (end > start && Seps.isSep(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    public static String strip(String s, String chars) {
        if (s == null || s.isEmpty()) return s;
        var start = 0;
        var end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    public static String cleanup(String input) {
        if (input == null || input.isEmpty()) return input;
        var clean = new StringBuilder(input.length());
        for (var c : input.toCharArray()) {
            clean.append(CLEAN_CHARS.indexOf(c) >= 0 ? ' ' : c);
        }
        var cleanString = clean.toString();

        var indices = new java.util.ArrayList<Integer>();
        for (var i = 0; i < cleanString.length(); i++) {
            if (Seps.isSep(cleanString.charAt(i))) indices.add(i);
        }
        var dots = new java.util.HashSet<Character>();
        if (!indices.isEmpty()) {
            var chars = cleanString.toCharArray();
            var potential = new java.util.ArrayList<Integer>();
            for (var i : indices) {
                if (potentialBefore(i, input) && potentialAfter(i, input)) potential.add(i);
            }
            // Extend leftward: a leading acronym dot ("S." in "S.W.A.T.") fails
            // potentialBefore (no i-2 sep) but should still be preserved when its
            // sibling at i+2 is already potential AND the run on the left is a
            // single character (otherwise "of.S.H.I.E.L.D" would absorb "of").
            for (var i : indices) {
                if (potential.contains(i)) continue;
                if (i - 1 < 0 || i + 1 >= input.length()) continue;
                if (Seps.isSep(input.charAt(i - 1)) || Seps.isSep(input.charAt(i + 1))) continue;
                boolean leftSingleChar = i - 2 < 0 || Seps.isSep(input.charAt(i - 2));
                if (leftSingleChar && potential.contains(i + 2)) potential.add(i);
            }
            var replace = new java.util.ArrayList<Integer>();
            for (var p : potential) {
                if (potential.contains(p - 2) || potential.contains(p + 2)) replace.add(p);
            }
            if (!replace.isEmpty()) {
                for (var r : replace) {
                    dots.add(input.charAt(r));
                    chars[r] = input.charAt(r);
                }
                cleanString = new String(chars);
            }
        }

        var stripChars = new StringBuilder();
        for (var c : Seps.CHARS.toCharArray()) {
            if (!dots.contains(c)) stripChars.append(c);
        }
        cleanString = strip(cleanString, stripChars.toString());
        return cleanString.replaceAll(" +", " ");
    }

    private static boolean potentialBefore(int i, String input) {
        return i - 1 >= 0 && i < input.length()
            && Seps.isSep(input.charAt(i))
            && i - 2 >= 0 && Seps.isSep(input.charAt(i - 2))
            && !Seps.isSep(input.charAt(i - 1));
    }

    private static boolean potentialAfter(int i, String input) {
        if (i + 2 >= input.length()) return true;
        return input.charAt(i + 2) == input.charAt(i) && !Seps.isSep(input.charAt(i + 1));
    }

    public static String reorderTitle(String title) {
        if (title == null) return null;
        var ltitle = title.toLowerCase(java.util.Locale.ROOT);
        for (var article : new String[]{"the"}) {
            for (var separator : new String[]{",", ", "}) {
                var suffix = separator + article;
                if (ltitle.endsWith(suffix)) {
                    return title.substring(title.length() - suffix.length() + separator.length())
                        + " " + title.substring(0, title.length() - suffix.length());
                }
            }
        }
        return title;
    }

    public static String titleText(String input) {
        if (input == null) return null;
        return reorderTitle(cleanup(input));
    }
}
