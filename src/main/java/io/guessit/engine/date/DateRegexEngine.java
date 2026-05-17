package io.guessit.engine.date;

import com.mirkoddd.sift.core.NamedCapture;
import com.mirkoddd.sift.core.Sift;
import com.mirkoddd.sift.core.SiftGlobalFlag;
import com.mirkoddd.sift.core.dsl.Assertion;
import com.mirkoddd.sift.core.dsl.Fragment;
import com.mirkoddd.sift.core.dsl.SiftPattern;
import io.guessit.engine.Seps;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.anyOf;
import static com.mirkoddd.sift.core.SiftPatterns.capture;
import static com.mirkoddd.sift.core.SiftPatterns.literal;
import static com.mirkoddd.sift.core.SiftPatterns.negativeLookahead;
import static com.mirkoddd.sift.core.SiftPatterns.negativeLookbehind;
import static com.mirkoddd.sift.core.SiftPatterns.withFlags;

/**
 * Responsible strictly for executing regex patterns to find potential date strings.
 */
final class DateRegexEngine {

    private DateRegexEngine() {}

    private static final String GROUP_FULL_DATE = "date";
    private static final String GROUP_PART_1 = "p1";
    private static final String GROUP_PART_2 = "p2";
    private static final String GROUP_PART_3 = "p3";

    private static final String[] NUMERIC_GROUPS = {GROUP_PART_1, GROUP_PART_2, GROUP_PART_3};

    private static final SiftPattern<Fragment> DATE_SEPARATOR = anyOf(literal("-"), literal("/"), literal("."), literal(" "));
    private static final SiftPattern<Fragment> EXTENDED_SEPARATOR = anyOf(literal("-"), literal("/"), literal("."), literal(" "), literal("x"));
    private static final SiftPattern<Fragment> ORDINAL_SUFFIX = optional().of(anyOf(literal("st"), literal("nd"), literal("rd"), literal("th")));

    private static final SiftPattern<Assertion> BOUNDARY_START = negativeLookbehind(exactly(1).digits());
    private static final SiftPattern<Assertion> BOUNDARY_END = negativeLookahead(exactly(1).digits());

    private static final NamedCapture FIRST_PART_1_TO_2_DIGITS = capture(GROUP_PART_1, between(1, 2).digits());
    private static final NamedCapture SECOND_PART_1_TO_2_DIGITS = capture(GROUP_PART_2, between(1, 2).digits());
    private static final NamedCapture THIRD_PART_1_TO_2_DIGITS = capture(GROUP_PART_3, between(1, 2).digits());
    private static final NamedCapture FIRST_PART_2_DIGITS = capture(GROUP_PART_1, exactly(2).digits());
    private static final NamedCapture THIRD_PART_2_DIGITS = capture(GROUP_PART_3, exactly(2).digits());
    private static final NamedCapture FIRST_PART_4_DIGITS = capture(GROUP_PART_1, exactly(4).digits());
    private static final NamedCapture THIRD_PART_4_DIGITS = capture(GROUP_PART_3, exactly(4).digits());

    private static final Pattern COMPACT_8_DIGIT = compile(Sift.fromAnywhere()
            .of(DATE_SEPARATOR).then().namedCapture(capture(GROUP_FULL_DATE, exactly(8).digits())).then().of(DATE_SEPARATOR));

    private static final Pattern COMPACT_6_DIGIT = compile(Sift.fromAnywhere()
            .of(DATE_SEPARATOR).then().namedCapture(capture(GROUP_FULL_DATE, exactly(6).digits())).then().of(DATE_SEPARATOR));

    private static final Pattern TWO_DIGIT_START = compileBounded(Sift.fromAnywhere()
            .namedCapture(FIRST_PART_2_DIGITS).then().of(DATE_SEPARATOR).then().namedCapture(SECOND_PART_1_TO_2_DIGITS).then().of(DATE_SEPARATOR).then().namedCapture(THIRD_PART_1_TO_2_DIGITS));

    private static final Pattern ONE_TWO_DIGIT_START = compileBounded(Sift.fromAnywhere()
            .namedCapture(FIRST_PART_1_TO_2_DIGITS).then().of(DATE_SEPARATOR).then().namedCapture(SECOND_PART_1_TO_2_DIGITS).then().of(DATE_SEPARATOR).then().namedCapture(THIRD_PART_2_DIGITS));

    private static final Pattern FOUR_DIGIT_START = compileBounded(Sift.fromAnywhere()
            .namedCapture(FIRST_PART_4_DIGITS).then().of(EXTENDED_SEPARATOR).then().namedCapture(SECOND_PART_1_TO_2_DIGITS).then().of(DATE_SEPARATOR).then().namedCapture(THIRD_PART_1_TO_2_DIGITS));

    private static final Pattern FOUR_DIGIT_END = compileBounded(Sift.fromAnywhere()
            .namedCapture(FIRST_PART_1_TO_2_DIGITS).then().of(DATE_SEPARATOR).then().namedCapture(SECOND_PART_1_TO_2_DIGITS).then().of(EXTENDED_SEPARATOR).then().namedCapture(THIRD_PART_4_DIGITS));

    private static final Pattern MONTH_WORD = compileBounded(Sift.fromAnywhere()
            .namedCapture(capture(GROUP_PART_1, between(1, 2).digits()))
            .then().of(ORDINAL_SUFFIX).then().of(DATE_SEPARATOR)
            .then().namedCapture(capture(GROUP_PART_2, between(3, 10).letters()))
            .then().of(DATE_SEPARATOR)
            .then().namedCapture(capture(GROUP_PART_3, exactly(4).digits())));

    private record PatternRoute(Pattern pattern, DateShape shape) {}

    private static final List<PatternRoute> ROUTES = List.of(
            new PatternRoute(COMPACT_8_DIGIT, DateShape.COMPACT_8_DIGIT),
            new PatternRoute(COMPACT_6_DIGIT, DateShape.COMPACT_6_DIGIT),
            new PatternRoute(TWO_DIGIT_START, DateShape.NUMERIC_SEPARATED),
            new PatternRoute(ONE_TWO_DIGIT_START, DateShape.NUMERIC_SEPARATED),
            new PatternRoute(FOUR_DIGIT_START, DateShape.NUMERIC_SEPARATED),
            new PatternRoute(FOUR_DIGIT_END, DateShape.NUMERIC_SEPARATED),
            new PatternRoute(MONTH_WORD, DateShape.MONTH_WORD)
    );

    /**
     * Extracts the first valid match from each pattern that respects the boundaries.
     */
    static List<RawDateMatch> findCandidates(String input) {
        List<RawDateMatch> candidates = new ArrayList<>();

        for (PatternRoute route : ROUTES) {
            Matcher matcher = route.pattern().matcher(input);

            if (matcher.find()) {
                int startIndex = matcher.start(GROUP_FULL_DATE);
                int endIndex = matcher.end(GROUP_FULL_DATE);

                if (areSeparatorsSurrounding(input, startIndex, endIndex)) {
                    String[] parts = extractNumericParts(matcher);
                    String rawDateMatch = matcher.group(GROUP_FULL_DATE);

                    candidates.add(new RawDateMatch(startIndex, endIndex, rawDateMatch, parts, route.shape()));
                }
            }
        }
        return candidates;
    }

    private static Pattern compileBounded(SiftPattern<Fragment> innerSequence) {
        var boundedPattern = Sift.fromAnywhere()
                .namedCapture(capture(GROUP_FULL_DATE, innerSequence))
                .precededByAssertion(BOUNDARY_START)
                .followedByAssertion(BOUNDARY_END);
        return compile(boundedPattern);
    }

    private static Pattern compile(SiftPattern<Fragment> pattern) {
        return Pattern.compile(withFlags(pattern, SiftGlobalFlag.CASE_INSENSITIVE).shake());
    }

    private static String[] extractNumericParts(Matcher matcher) {
        List<String> extractedParts = new ArrayList<>();
        for (String groupName : NUMERIC_GROUPS) {
            try {
                String value = matcher.group(groupName);
                if (value != null) {
                    extractedParts.add(value);
                }
            } catch (IllegalArgumentException _) {}
        }
        return extractedParts.toArray(new String[0]);
    }

    private static boolean areSeparatorsSurrounding(String input, int startIndex, int endIndex) {
        boolean isSafeBefore = startIndex == 0 || Seps.isSep(input.charAt(startIndex - 1));
        boolean isSafeAfter = endIndex == input.length() || Seps.isSep(input.charAt(endIndex));
        return isSafeBefore && isSafeAfter;
    }
}