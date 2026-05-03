package io.guessit.engine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Port of guessit/rules/common/date.py. */
public final class DatePatterns {
    private DatePatterns() {}

    public record Result(int start, int end, LocalDate date) {}

    private static final String DSEP = "[-/ \\.]";
    private static final String DSEP_BIS = "[-/ \\.x]";

    /** Each regex captures group 1 = the date span. */
    public static final List<Pattern> REGEXPS = List.of(
        Pattern.compile(DSEP + "((\\d{8}))" + DSEP, Pattern.CASE_INSENSITIVE),
        Pattern.compile(DSEP + "((\\d{6}))" + DSEP, Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{2})" + DSEP + "(\\d{1,2})" + DSEP + "(\\d{1,2}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{1,2})" + DSEP + "(\\d{1,2})" + DSEP + "(\\d{2}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{4})" + DSEP_BIS + "(\\d{1,2})" + DSEP + "(\\d{1,2}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{1,2})" + DSEP + "(\\d{1,2})" + DSEP_BIS + "(\\d{4}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[^\\d])((\\d{1,2}(?:st|nd|rd|th)?" + DSEP + "(?:[a-z]{3,10})" + DSEP + "\\d{4}))(?:$|[^\\d])", Pattern.CASE_INSENSITIVE)
    );

    public static boolean validYear(int year) { return 1920 <= year && year < 2030; }
    public static boolean validWeek(int week) { return 1 <= week && week < 53; }

    public static Optional<Result> search(String input, Boolean yearFirst, Boolean dayFirst) {
        for (var p : REGEXPS) {
            var matcher = p.matcher(input);
            int from = 0;
            while (matcher.find(from)) {
                int s = matcher.start(1);
                int e = matcher.end(1);
                from = s + 1;

                if (!sepsSurround(input, s, e)) continue;

                int gc = matcher.groupCount();
                String[] parts = new String[gc - 1];
                for (int i = 0; i < parts.length; i++) parts[i] = matcher.group(2 + i);

                Boolean df = dayFirst;
                if (Boolean.TRUE.equals(yearFirst) && df == null) df = false;
                if (df == null) df = guessDayFirst(parts);

                var date = parseAllValid(matcher.group(1), yearFirst, df);
                if (date.isPresent()) {
                    return Optional.of(new Result(s, e, date.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean sepsSurround(String input, int start, int end) {
        boolean before = start == 0 || Seps.isSep(input.charAt(start - 1));
        boolean after = end == input.length() || Seps.isSep(input.charAt(end));
        return before && after;
    }

    private static Boolean guessDayFirst(String[] parts) {
        if (parts.length == 0) return null;
        var first = parts[0];
        var last = parts[parts.length - 1];
        if (isInt(first) && first.length() >= 4 && validYear(Integer.parseInt(first.substring(0, 4)))) return false;
        if (isInt(last) && last.length() >= 4 && validYear(Integer.parseInt(last.substring(last.length() - 4)))) return true;
        if (isInt(first) && Integer.parseInt(first.substring(0, Math.min(2, first.length()))) > 31) return false;
        if (isInt(last) && Integer.parseInt(last.substring(Math.max(0, last.length() - 2))) > 31) return true;
        return null;
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static Optional<LocalDate> parseAllValid(String raw, Boolean yearFirst, Boolean dayFirst) {
        var seps = new String[]{"-", "/", " ", ".", "x"};
        for (var sep : seps) {
            var attempt = tryParse(raw.replace(sep, "-"), yearFirst, dayFirst);
            if (attempt.isPresent() && validYear(attempt.get().getYear())) return attempt;
        }
        return Optional.empty();
    }

    /** Try the formatter combinations Python dateutil would consider. */
    private static Optional<LocalDate> tryParse(String raw, Boolean yearFirst, Boolean dayFirst) {
        if (raw.matches("\\d{8}")) {
            try { return Optional.of(LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)); }
            catch (Exception ignore) {}
        }
        if (raw.matches("\\d{6}")) {
            int y = 2000 + Integer.parseInt(raw.substring(0, 2));
            int mo = Integer.parseInt(raw.substring(2, 4));
            int d = Integer.parseInt(raw.substring(4, 6));
            try { return Optional.of(LocalDate.of(y, mo, d)); } catch (Exception ignore) {}
        }
        var monthPattern = Pattern.compile("^(\\d{1,2})(?:st|nd|rd|th)?-([A-Za-z]{3,10})-(\\d{4})$", Pattern.CASE_INSENSITIVE);
        var mm = monthPattern.matcher(raw);
        if (mm.matches()) {
            int d = Integer.parseInt(mm.group(1));
            int mo = monthIndex(mm.group(2));
            int y = Integer.parseInt(mm.group(3));
            if (mo > 0) try { return Optional.of(LocalDate.of(y, mo, d)); } catch (Exception ignore) {}
        }
        var numPattern = Pattern.compile("^(\\d{1,4})-(\\d{1,2})-(\\d{1,4})$");
        var nm = numPattern.matcher(raw);
        if (!nm.matches()) return Optional.empty();
        int a = Integer.parseInt(nm.group(1));
        int b = Integer.parseInt(nm.group(2));
        int c = Integer.parseInt(nm.group(3));

        // Python dateutil: try combinations matching (dayfirst_opts x yearfirst_opts)
        var dOpts = dayFirst != null ? new Boolean[]{dayFirst} : new Boolean[]{true, false};
        var yOpts = yearFirst != null ? new Boolean[]{yearFirst} : new Boolean[]{false, true};
        Optional<LocalDate> first = Optional.empty();
        for (var df : dOpts) {
            for (var yf : yOpts) {
                var result = tryParseNumeric(a, b, c, yf, df);
                if (result.isPresent()) {
                    if (validYear(result.get().getYear())) return result;
                    if (first.isEmpty()) first = result;
                }
            }
        }
        return first;
    }

    private static Optional<LocalDate> tryParseNumeric(int a, int b, int c, Boolean yearFirst, Boolean dayFirst) {
        if (Boolean.TRUE.equals(yearFirst)) {
            int y = a >= 100 ? a : 2000 + a;
            try { return Optional.of(LocalDate.of(y, b, c)); } catch (Exception ignore) {}
        }
        if (c >= 100) {
            int y = c;
            if (Boolean.TRUE.equals(dayFirst)) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
            try { return Optional.of(LocalDate.of(y, a, b)); } catch (Exception ignore) {}
            if (Boolean.FALSE.equals(dayFirst)) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
        } else {
            int y = 2000 + c;
            if (Boolean.TRUE.equals(dayFirst)) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
            try { return Optional.of(LocalDate.of(y, a, b)); } catch (Exception ignore) {}
            if (Boolean.FALSE.equals(dayFirst)) try { return Optional.of(LocalDate.of(y, b, a)); } catch (Exception ignore) {}
        }
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
