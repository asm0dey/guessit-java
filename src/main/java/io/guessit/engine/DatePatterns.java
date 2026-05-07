package io.guessit.engine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Port of guessit/rules/common/date.py — date detection and parsing.
 *
 * <p>{@link #search} scans the input with a battery of regexes covering the
 * usual filename-date shapes (YYYYMMDD, YY-MM-DD, DD-MM-YYYY, "5 Sep 2020",
 * etc.) and resolves the ambiguous numeric forms via dateutil-style
 * day/year-first heuristics implemented in {@link #guessDayFirst} and
 * {@link #tryParseNumeric}.
 *
 * <p>Each regex captures the date span in group 1 and the individual numeric
 * components in groups 2..N. Trailing optional ordinal suffixes ("st", "nd",
 * "rd", "th") on the day part are accepted but stripped before parsing.
 */
public final class DatePatterns {
    private DatePatterns() {}

    public record Result(int start, int end, LocalDate date) {}

    private static final String DSEP = "[-/ .]";
    private static final String DSEP_BIS = "[-/ .x]";
    private static final String D12 = "(\\d{1,2})";

    private static final Pattern NORMALIZE_SEP = Pattern.compile("[/ .x]");
    private static final Pattern EIGHT_DIGITS = Pattern.compile("\\d{8}");
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    private static final Pattern MONTH_NAME_PATTERN = Pattern.compile(
        "^(\\d{1,2})(?:st|nd|rd|th)?-([A-Z]{3,10})-(\\d{4})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM_PATTERN = Pattern.compile("^(\\d{1,4})-(\\d{1,2})-(\\d{1,4})$");

    /** Each regex captures group 1 = the date span. */
    public static final List<Pattern> REGEXPS = List.of(
        Pattern.compile("[-/ .](\\d{8})[-/ .]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("[-/ .](\\d{6})[-/ .]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|\\D)((\\d{2})" + DSEP + D12 + DSEP + D12 + ")(?:$|\\D)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|\\D)(" + D12 + DSEP + D12 + DSEP + "(\\d{2}))(?:$|\\D)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|\\D)((\\d{4})" + DSEP_BIS + D12 + DSEP + D12 + ")(?:$|\\D)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|\\D)(" + D12 + DSEP + D12 + DSEP_BIS + "(\\d{4}))(?:$|\\D)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|\\D)(\\d{1,2}(?:st|nd|rd|th)?[-/ .][a-z]{3,10}[-/ .]\\d{4})(?:$|\\D)", Pattern.CASE_INSENSITIVE)
    );

    public static boolean validYear(int year) { return 1920 <= year && year < 2030; }
    public static boolean validWeek(int week) { return 1 <= week && week < 53; }

    public static Optional<Result> search(String input, Boolean yearFirst, Boolean dayFirst) {
        // Mirror python's date.py: take only the FIRST match per regex. If that
        // match fails to parse to a valid date (e.g. month=18 in "21.18-5"),
        // skip to the next regex pattern rather than advancing within the
        // current one — otherwise a downstream "18-5-4" would resolve to a
        // bogus date in inputs like "...2x21.18-5-4..." where python rejects
        // every candidate.
        for (var p : REGEXPS) {
            var matcher = p.matcher(input);
            if (!matcher.find()) continue;
            int s = matcher.start(1);
            int e = matcher.end(1);
            if (!sepsSurround(input, s, e)) continue;

            int gc = matcher.groupCount();
            String[] parts = new String[gc - 1];
            for (int i = 0; i < parts.length; i++) parts[i] = matcher.group(2 + i);

            Boolean df = dayFirst;
            if (Boolean.TRUE.equals(yearFirst) && df == null) df = false;
            if (df == null) df = guessDayFirst(parts).orElse(null);

            var date = parseAllValid(matcher.group(1), yearFirst, df);
            if (date.isPresent()) {
                return Optional.of(new Result(s, e, date.get()));
            }
        }
        return Optional.empty();
    }

    private static boolean sepsSurround(String input, int start, int end) {
        boolean before = start == 0 || Seps.isSep(input.charAt(start - 1));
        boolean after = end == input.length() || Seps.isSep(input.charAt(end));
        return before && after;
    }

    /**
     * Heuristically resolves day-first vs. month-first when neither is forced.
     *
     * <p>Order of evidence:
     * <ol>
     *   <li>If the first component looks like a 4-digit year → year-first
     *       (so the second component is the month, not the day).</li>
     *   <li>If the last component looks like a 4-digit year → day-first is
     *       likely (matches European DD-MM-YYYY convention).</li>
     *   <li>If the first component {@code > 31} → it can't be a day, so
     *       it must be the year (year-first).</li>
     *   <li>If the last component {@code > 31} → it must be the year, and
     *       the first component is most often a day (day-first).</li>
     * </ol>
     * Returns {@code null} when none of the heuristics fires; callers then
     * fall back to dateutil's combinatorial try-everything approach.
     */
    private static Optional<Boolean> guessDayFirst(String[] parts) {
        if (parts.length == 0) return Optional.empty();
        var first = parts[0];
        var last = parts[parts.length - 1];
        if (isInt(first) && first.length() >= 4 && validYear(Integer.parseInt(first.substring(0, 4)))) return Optional.of(false);
        if (isInt(last) && last.length() >= 4 && validYear(Integer.parseInt(last.substring(last.length() - 4)))) return Optional.of(true);
        if (isInt(first) && Integer.parseInt(first.substring(0, Math.min(2, first.length()))) > 31) return Optional.of(false);
        if (isInt(last) && Integer.parseInt(last.substring(Math.max(0, last.length() - 2))) > 31) return Optional.of(true);
        return Optional.empty();
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static Optional<LocalDate> parseAllValid(String raw, Boolean yearFirst, Boolean dayFirst) {
        var normalized = NORMALIZE_SEP.matcher(raw).replaceAll("-");
        var attempt = tryParse(normalized, yearFirst, dayFirst);
        if (attempt.isPresent() && validYear(attempt.get().getYear())) return attempt;
        return Optional.empty();
    }

    /** Try the formatter combinations Python dateutil would consider. */
    private static Optional<LocalDate> tryParse(String raw, Boolean yearFirst, Boolean dayFirst) {
        var p = parseEightDigits(raw);
        if (p.isPresent()) return p;
        p = parseSixDigits(raw);
        if (p.isPresent()) return p;
        p = parseMonthName(raw);
        if (p.isPresent()) return p;
        return parseNumericSeparated(raw, yearFirst, dayFirst);
    }

    private static Optional<LocalDate> parseEightDigits(String raw) {
        if (!EIGHT_DIGITS.matcher(raw).matches()) return Optional.empty();
        try { return Optional.of(LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)); }
        catch (Exception _) { return Optional.empty(); }
    }

    private static Optional<LocalDate> parseSixDigits(String raw) {
        if (!SIX_DIGITS.matcher(raw).matches()) return Optional.empty();
        int y = 2000 + Integer.parseInt(raw.substring(0, 2));
        int mo = Integer.parseInt(raw.substring(2, 4));
        int d = Integer.parseInt(raw.substring(4, 6));
        try { return Optional.of(LocalDate.of(y, mo, d)); }
        catch (Exception _) { return Optional.empty(); }
    }

    private static Optional<LocalDate> parseMonthName(String raw) {
        var mm = MONTH_NAME_PATTERN.matcher(raw);
        if (!mm.matches()) return Optional.empty();
        int d = Integer.parseInt(mm.group(1));
        int mo = monthIndex(mm.group(2));
        int y = Integer.parseInt(mm.group(3));
        if (mo <= 0) return Optional.empty();
        try { return Optional.of(LocalDate.of(y, mo, d)); }
        catch (Exception _) { return Optional.empty(); }
    }

    /** Python dateutil: try combinations matching (dayfirst_opts x yearfirst_opts). */
    private static Optional<LocalDate> parseNumericSeparated(String raw, Boolean yearFirst, Boolean dayFirst) {
        var nm = NUM_PATTERN.matcher(raw);
        if (!nm.matches()) return Optional.empty();
        int a = Integer.parseInt(nm.group(1));
        int b = Integer.parseInt(nm.group(2));
        int c = Integer.parseInt(nm.group(3));
        var dOpts = dayFirst != null ? new Boolean[]{dayFirst} : new Boolean[]{true, false};
        var yOpts = yearFirst != null ? new Boolean[]{yearFirst} : new Boolean[]{false, true};
        Optional<LocalDate> first = Optional.empty();
        for (var df : dOpts) {
            for (var yf : yOpts) {
                var result = tryParseNumeric(a, b, c, yf, df);
                if (result.isEmpty()) continue;
                if (validYear(result.get().getYear())) return result;
                if (first.isEmpty()) first = result;
            }
        }
        return first;
    }

    private static Optional<LocalDate> tryParseNumeric(int a, int b, int c, Boolean yearFirst, Boolean dayFirst) {
        if (Boolean.TRUE.equals(yearFirst)) {
            int y = a >= 100 ? a : 2000 + a;
            try { return Optional.of(LocalDate.of(y, b, c)); } catch (Exception _) { /* invalid date — fall through */ }
        }
        int y = c >= 100 ? c : 2000 + c;
        if (Boolean.TRUE.equals(dayFirst)) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception _) { /* invalid — fall through */ }
        try { return Optional.of(LocalDate.of(y, a, b)); } catch (Exception _) { /* invalid — fall through */ }
        if (Boolean.FALSE.equals(dayFirst)) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception _) { /* invalid — fall through */ }
        return Optional.empty();
    }

    private static int monthIndex(String name) {
        var months = List.of("january","february","march","april","may","june",
            "july","august","september","october","november","december");
        var lc = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < months.size(); i++) {
            if (months.get(i).startsWith(lc)) return i + 1;
        }
        return -1;
    }
}
