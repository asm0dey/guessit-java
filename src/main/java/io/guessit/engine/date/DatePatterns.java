package io.guessit.engine.date;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Port of guessit/rules/common/date.py — date detection and parsing.
 *
 * <p>{@link #search} orchestrates the date detection flow by retrieving
 * regex candidates from {@link DateRegexEngine}, resolving heuristics via
 * {@link DateHeuristics}, and parsing the final date using {@link DateParser}.
 */
public final class DatePatterns {

    private DatePatterns() {}

    public record Result(int start, int end, LocalDate date) {}

    /**
     * Scans the input string to detect, resolve, and parse a valid date.
     */
    public static Optional<Result> search(String input, Boolean yearFirst, Boolean dayFirst) {
        return DateRegexEngine.findCandidates(input)
                .stream()
                .map(candidate -> evaluateCandidate(candidate, yearFirst, dayFirst))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Result> evaluateCandidate(RawDateMatch candidate, Boolean yearFirst, Boolean dayFirst) {
        Boolean resolvedDayFirst = resolveDayFirst(yearFirst, dayFirst, candidate.parts());

        return DateParser.parse(candidate, yearFirst, resolvedDayFirst)
                .map(parsedDate -> new Result(candidate.start(), candidate.end(), parsedDate));
    }

    private static Boolean resolveDayFirst(Boolean yearFirst, Boolean dayFirst, String[] parts) {
        if (dayFirst != null) return dayFirst;
        if (Boolean.TRUE.equals(yearFirst)) return false;

        return DateHeuristics.guessDayFirst(parts).orElse(null);
    }

    public static boolean validYear(int year) {
        return DateHeuristics.validYear(year);
    }

    public static boolean validWeek(int week) {
        return DateHeuristics.validWeek(week);
    }
}