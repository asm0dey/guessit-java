package io.guessit.util;

public final class Size extends Quantity {

    Size(double value, String unit) {
        super(value, unit);
    }

    public static Size fromString(String input) {
        if (Quantity.parse(input) instanceof Size s) return s;
        throw new IllegalArgumentException("Not a size: " + input);
    }
}