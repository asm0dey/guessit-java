package io.guessit.util;

public sealed class Quantity permits Size, BitRate {
    public final double value;
    public final String unit;
    protected Quantity(double value, String unit) {
        this.value = value;
        this.unit = unit;
    }
    public double value() { return value; }
    public String unit() { return unit; }
    public String format() {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return ((long) value) + " " + unit;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit);
    }
    @Override public boolean equals(Object o) {
        return o instanceof Quantity q && Double.compare(value, q.value) == 0 && unit.equals(q.unit);
    }
    @Override public int hashCode() { return java.util.Objects.hash(value, unit); }
    @Override public String toString() { return format(); }

    public static Quantity parse(String s) {
        var t = s.trim();
        var lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("bps") || lower.endsWith("bit") || lower.endsWith("bits"))
            return BitRate.fromString(t);
        return Size.fromString(t);
    }
}
