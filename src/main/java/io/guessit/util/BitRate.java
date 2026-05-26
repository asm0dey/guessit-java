package io.guessit.util;

public final class BitRate extends Quantity {

    BitRate(double value, String unit) {
        super(value, unit);
    }

    public static BitRate fromString(String input) {
        if (Quantity.parse(input) instanceof BitRate b) return b;
        throw new IllegalArgumentException("Not a bit rate: " + input);
    }
}