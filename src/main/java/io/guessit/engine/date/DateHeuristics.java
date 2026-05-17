package io.guessit.engine.date;

import java.util.Optional;

/**
 * Encapsulates the dateutil-style heuristics used to guess ambiguous date formats.
 */
final class DateHeuristics {

    private DateHeuristics() {}

    static boolean validYear(int year) {
        return 1920 <= year && year < 2030;
    }

    static boolean validWeek(int week) {
        return 1 <= week && week < 53;
    }

    /**
     * Heuristically resolves day-first vs. month-first when neither is forced.
     *
     * <p>Order of evidence:
     * <ol>
     * <li>If the first component looks like a 4-digit year → year-first
     * (so the second component is the month, not the day).</li>
     * <li>If the last component looks like a 4-digit year → day-first is
     * likely (matches European DD-MM-YYYY convention).</li>
     * <li>If the first component {@code > 31} → it can't be a day, so
     * it must be the year (year-first).</li>
     * <li>If the last component {@code > 31} → it must be the year, and
     * the first component is most often a day (day-first).</li>
     * </ol>
     */
    static Optional<Boolean> guessDayFirst(String[] parts) {
        if (parts.length == 0) return Optional.empty();

        var first = parts[0];
        var last = parts[parts.length - 1];

        if (isInt(first) && first.length() >= 4 && validYear(Integer.parseInt(first.substring(0, 4)))) {
            return Optional.of(false);
        }
        if (isInt(last) && last.length() >= 4 && validYear(Integer.parseInt(last.substring(last.length() - 4)))) {
            return Optional.of(true);
        }
        if (isInt(first) && Integer.parseInt(first.substring(0, Math.min(2, first.length()))) > 31) {
            return Optional.of(false);
        }
        if (isInt(last) && Integer.parseInt(last.substring(Math.max(0, last.length() - 2))) > 31) {
            return Optional.of(true);
        }

        return Optional.empty();
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}