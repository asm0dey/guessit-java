package io.guessit.util;

public record Quantity(double value, String unit) {
    public String format() {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return ((long) value) + " " + unit;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit);
    }

    public static Quantity parse(String s) {
        var parts = s.trim().split("\\s+");
        if (parts.length != 2) throw new IllegalArgumentException("Quantity expects 'value unit', got: " + s);
        return new Quantity(Double.parseDouble(parts[0]), parts[1]);
    }
}
