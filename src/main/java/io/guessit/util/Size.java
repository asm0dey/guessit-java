package io.guessit.util;

import io.guessit.engine.Seps;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Size extends Quantity {
    private static final Pattern P = Pattern.compile("(?<m>\\d+(?:\\.\\d+)?)(?<u>[^\\d]+)?");
    private Size(double v, String u) { super(v, u); }
    public static Size fromString(String s) {
        var m = P.matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("Not a size: " + s);
        var raw = m.group("u") == null ? "" : m.group("u");
        var u = trimSeps(raw).toUpperCase(Locale.ROOT);
        return new Size(Double.parseDouble(m.group("m")), u);
    }
    private static String trimSeps(String s) {
        int a = 0, b = s.length();
        while (a < b && Seps.isSep(s.charAt(a))) a++;
        while (b > a && Seps.isSep(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
}
