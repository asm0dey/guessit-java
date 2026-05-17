package io.guessit.engine.numerals;

import com.mirkoddd.sift.core.NamedCapture;
import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import com.mirkoddd.sift.core.engine.SiftCompiledPattern;

import static com.mirkoddd.sift.core.Sift.between;
import static com.mirkoddd.sift.core.SiftPatterns.capture;

/**
 * Handles validation and parsing of standard digital numerals.
 */
final class DigitNumerals implements RawNumeralParser {

    static final DigitNumerals INSTANCE = new DigitNumerals();

    private DigitNumerals() {
    }

    static final SiftPattern<Fragment> PATTERN = between(1, 4).digits();

    private static final String CLEAN_GROUP_NAME = "number";

    private static final NamedCapture CLEAN_GROUP = capture(CLEAN_GROUP_NAME, PATTERN);

    private static final SiftCompiledPattern CLEAN_PATTERN = Sift.fromAnywhere()
            .namedCapture(CLEAN_GROUP)
            .sieve();

    @Override
    public Integer tryParse(String value) {
        String digits = CLEAN_PATTERN.extractGroups(value).get(CLEAN_GROUP_NAME);

        if (digits == null) {
            return null;
        }

        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException _) {
            return null;
        }
    }
}