package io.guessit.engine.date;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class DateParser {

    private static final int CENTURY_BASE = 2000;
    private static final int LAST_CENTURY_BASE = 1900;
    private static final int FOUR_DIGIT_YEAR_THRESHOLD = 100;
    private static final int REQUIRED_PARTS_COUNT = 3;

    /**
     * Pivot at 30: Years 00-29 resolve to 2000-2029 (modern media), while 30-99 resolve to 1930-1999.
     * This safely covers almost a century of cinema history without breaking current release dates.
     */
    private static final int TWO_DIGIT_PIVOT = 30;

    private static final int PART_1 = 0;
    private static final int PART_2 = 1;
    private static final int PART_3 = 2;

    private DateParser() {}

    static Optional<LocalDate> parse(RawDateMatch match, Boolean yearFirst, Boolean dayFirst) {
        Optional<LocalDate> attempt = switch (match.shape()) {
            case COMPACT_8_DIGIT -> parseEightDigits(match.rawDate());
            case COMPACT_6_DIGIT -> parseSixDigits(match.rawDate());
            case MONTH_WORD -> parseMonthName(match.parts());
            case NUMERIC_SEPARATED -> parseNumericSeparated(match.parts(), yearFirst, dayFirst);
        };

        return attempt.filter(date -> DateHeuristics.validYear(date.getYear()));
    }

    private static Optional<LocalDate> parseEightDigits(String rawDigits) {
        try {
            return Optional.of(LocalDate.parse(rawDigits, DateTimeFormatter.BASIC_ISO_DATE));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> parseSixDigits(String rawDigits) {
        try {
            int rawYear = Integer.parseInt(rawDigits.substring(0, 2));
            int year = resolveYear(rawYear);
            int month = Integer.parseInt(rawDigits.substring(2, 4));
            int day = Integer.parseInt(rawDigits.substring(4, 6));

            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException | NumberFormatException _) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> parseMonthName(String[] parts) {
        if (parts.length < REQUIRED_PARTS_COUNT) return Optional.empty();

        try {
            int day = Integer.parseInt(parts[PART_1]);
            int month = monthIndex(parts[PART_2]);
            int year = resolveYear(Integer.parseInt(parts[PART_3]));

            if (month <= 0) return Optional.empty();

            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException | NumberFormatException _) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> parseNumericSeparated(String[] parts, Boolean yearFirst, Boolean dayFirst) {
        if (parts.length < REQUIRED_PARTS_COUNT) return Optional.empty();

        int part1, part2, part3;
        try {
            part1 = Integer.parseInt(parts[PART_1]);
            part2 = Integer.parseInt(parts[PART_2]);
            part3 = Integer.parseInt(parts[PART_3]);
        } catch (NumberFormatException _) {
            return Optional.empty();
        }

        return generateStrategies(yearFirst, dayFirst).stream()
                .map(strategy -> tryParseNumeric(part1, part2, part3, strategy.isYearFirst(), strategy.isDayFirst()))
                .flatMap(Optional::stream)
                .filter(date -> DateHeuristics.validYear(date.getYear()))
                .findFirst();
    }

    private static int resolveYear(int value) {
        if (value >= FOUR_DIGIT_YEAR_THRESHOLD) {
            return value;
        }
        return value < TWO_DIGIT_PIVOT ? CENTURY_BASE + value : LAST_CENTURY_BASE + value;
    }

    private static Optional<LocalDate> tryParseNumeric(int part1, int part2, int part3, boolean isYearFirst, boolean isDayFirst) {
        if (isYearFirst) {
            int year = resolveYear(part1);
            return tryYMD(year, part2, part3);
        }

        int year = resolveYear(part3);

        if (isDayFirst) {
            return tryDMY(part1, part2, year)
                    .or(() -> tryMDY(part1, part2, year));
        }

        return tryMDY(part1, part2, year)
                .or(() -> tryDMY(part1, part2, year));
    }

    private static Optional<LocalDate> tryYMD(int year, int month, int day) {
        return safeDateOf(year, month, day);
    }

    private static Optional<LocalDate> tryDMY(int day, int month, int year) {
        return safeDateOf(year, month, day);
    }

    private static Optional<LocalDate> tryMDY(int month, int day, int year) {
        return safeDateOf(year, month, day);
    }

    private static Optional<LocalDate> safeDateOf(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException _) {
            return Optional.empty();
        }
    }

    private static int monthIndex(String name) {
        if (name == null || name.length() < 3) return -1;

        return switch (name.substring(0, 3).toLowerCase(Locale.ROOT)) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> -1;
        };
    }

    private record ParsingStrategy(boolean isYearFirst, boolean isDayFirst) {}

    private static List<ParsingStrategy> generateStrategies(Boolean yearFirst, Boolean dayFirst) {
        if (yearFirst != null && dayFirst != null) {
            return List.of(new ParsingStrategy(yearFirst, dayFirst));
        }

        if (yearFirst != null) {
            return List.of(
                    new ParsingStrategy(yearFirst, true),
                    new ParsingStrategy(yearFirst, false)
            );
        }

        if (dayFirst != null) {
            return List.of(
                    new ParsingStrategy(false, dayFirst),
                    new ParsingStrategy(true, dayFirst)
            );
        }

        return List.of(
                new ParsingStrategy(false, true),
                new ParsingStrategy(true, true),
                new ParsingStrategy(false, false),
                new ParsingStrategy(true, false)
        );
    }
}