package io.guessit.rules.property;

import io.guessit.engine.*;
import io.guessit.engine.numerals.Numerals;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.fromAnywhere;
import static com.mirkoddd.sift.core.Sift.optional;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Detects part numbers from {@code (pt|part)-?\d+} or {@code (pt|part)-?[Roman numeral]}
 * patterns, e.g. {@code Part2}, {@code pt3}, {@code PartIII}.
 *
 * <p>Value range: 0 < value < 100. Matches must be surrounded by separators.
 */
public final class PartExtractor implements Extractor {

    private static final int MIN_PART_NUMBER = 0;
    private static final int MAX_PART_NUMBER = 100;
    private static final String NUMERAL_GROUP = "num";

    private static final Pattern PART_PATTERN = buildPartPattern();

    private static Pattern buildPartPattern() {
        var anyOfParts = anyOf(literal("pt"), literal("part"));
        var optionalSeparator = optional().of(Abbreviations.SEPS_NO_FS_PATTERN);

        var numeralNameCapture = capture(NUMERAL_GROUP, Numerals.NUMERAL_PATTERN);

        var partPattern = fromAnywhere().of(anyOfParts)
                .followedBy(optionalSeparator)
                .then()
                .namedCapture(numeralNameCapture);

        return Pattern.compile(partPattern.shake(), Pattern.CASE_INSENSITIVE);
    }

    @Override
    public String name() {
        return "part";
    }

    @Override
    public String description() {
        return "part number (Part 1, Pt II, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var sepsValidator = Validators.sepsSurround(input);

        PART_PATTERN.matcher(input).results()
                .map(matchResult -> tryCreateMatch(matchResult, sepsValidator))
                .flatMap(Optional::stream)
                .forEach(ctx.matches::add);
    }

    private Optional<Match> tryCreateMatch(MatchResult matchResult, Predicate<Match> sepsValidator) {
        String rawNumeral = matchResult.group(NUMERAL_GROUP);

        return parseValidPartNumber(rawNumeral)
                .map(partNumber -> mapToMatch(matchResult, partNumber))
                .filter(sepsValidator);
    }

    private Match mapToMatch(MatchResult matchResult, Integer partNumber) {
        return new Match(
                MatchName.PART,
                partNumber,
                matchResult.start(),
                matchResult.end(),
                matchResult.group(),
                priority(),
                Set.of(),
                false
        );
    }

    private static Optional<Integer> parseValidPartNumber(String raw) {
        return Numerals.tryParseOptional(raw)
                .filter(PartExtractor::isInRange);
    }

    private static boolean isInRange(Integer partNumber) {
        return partNumber > MIN_PART_NUMBER && partNumber < MAX_PART_NUMBER;
    }
}