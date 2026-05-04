package io.guessit.util;

import io.guessit.engine.Seps;
import java.util.Locale;
import java.util.regex.Pattern;

public final class BitRate extends Quantity {
    private static final Pattern P = Pattern.compile("(?<m>\\d+(?:\\.\\d+)?)(?<u>[^\\d]+)?");
    private BitRate(double v, String u) { super(v, u); }
    public static BitRate fromString(String s) {
        var m = P.matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("Not a bit rate: " + s);
        var raw = m.group("u") == null ? "" : m.group("u");
        var u = trimSeps(raw);
        u = capitalise(u);
        u = u.replace("bits", "bps").replace("bit", "bps");
        return new BitRate(Double.parseDouble(m.group("m")), u);
    }
    private static String capitalise(String s) {
        if (s.isEmpty()) return s;
        var lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
    private static String trimSeps(String s) {
        int a = 0, b = s.length();
        while (a < b && Seps.isSep(s.charAt(a))) a++;
        while (b > a && Seps.isSep(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
}
