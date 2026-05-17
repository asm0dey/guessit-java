package io.guessit.util;

import com.mirkoddd.sift.core.engine.SiftCompiledPattern;
import io.guessit.engine.Seps;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.capture;

public abstract sealed class Quantity permits Size, BitRate {

    private final double value;
    private final String unit;

    protected Quantity(double value, String unit) {
        this.value = value;
        this.unit = unit;
    }

    public double value() {
        return value;
    }

    public String unit() {
        return unit;
    }

    public String format() {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return ((long) value) + " " + unit;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, unit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quantity quantity = (Quantity) o;
        return Double.compare(quantity.value, value) == 0 && unit.equals(quantity.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, unit);
    }

    private static final String GRP_MAGNITUDE = "magnitude";
    private static final String GRP_UNIT = "unit";

    private static final SiftCompiledPattern PATTERN = buildPattern();

    private static SiftCompiledPattern buildPattern() {
        var decimals = exactly(1).character('.').then().oneOrMore().digits();
        var magnitudeBlock = oneOrMore().digits().followedBy(optional().of(decimals));
        var unitBlock = fromAnywhere().namedCapture(capture(GRP_UNIT, oneOrMore().nonDigits()));

        return fromStart()
                .namedCapture(capture(GRP_MAGNITUDE, magnitudeBlock))
                .then().optional().of(unitBlock)
                .andNothingElse()
                .sieve();
    }

    public static Quantity parse(String s) {
        String input = s.trim();
        Map<String, String> match = PATTERN.extractGroups(input);

        if (match.isEmpty()) {
            throw new IllegalArgumentException("Not a valid quantity: " + input);
        }

        double magnitude = Double.parseDouble(match.get(GRP_MAGNITUDE));
        String rawUnit = Seps.trim(match.getOrDefault(GRP_UNIT, ""));
        String lowerUnit = rawUnit.toLowerCase(Locale.ROOT);

        if (lowerUnit.endsWith("bps") || lowerUnit.endsWith("bit") || lowerUnit.endsWith("bits")) {
            String unit = capitalise(rawUnit)
                    .replace("bits", "bps")
                    .replace("bit", "bps");
            return new BitRate(magnitude, unit);
        }

        return new Size(magnitude, rawUnit.toUpperCase(Locale.ROOT));
    }

    private static String capitalise(String text) {
        if (text.isEmpty()) return text;
        var lower = text.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}