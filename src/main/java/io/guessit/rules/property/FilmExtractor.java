package io.guessit.rules.property;

import io.guessit.engine.*;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects film numbers from {@code f\d{1,2}} patterns, e.g. {@code f01}.
 *
 * <p>Pattern must be surrounded by separators (seps_surround) and the numeric
 * part is limited to 1–2 digits to avoid false positives on longer tokens.
 *
 * <p>postProcess builds a {@code film_title} from the leading hole in the same
 * filepart marker before the film match.
 */
public final class FilmExtractor implements Extractor {
    private static final Pattern P = Pattern.compile("(?i)f(?<n>\\d{1,2})");

    @Override public String name() { return MatchName.FILM.toString().toLowerCase(); }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = P.matcher(input);
        while (m.find()) {
            // Use the full match span for separator-surround check.
            var head = new Match(MatchName.FILM, null, m.start(), m.end(), m.group(), priority(), Set.of(), false);
            if (!seps.test(head)) continue;

            // Span covers the full "fNN" so the leading 'f' doesn't leak into
            // the surrounding title hole.
            ctx.matches.add(new Match(MatchName.FILM, Integer.parseInt(m.group("n")),
                m.start(), m.end(), m.group(), priority(), Set.of(), false));
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        var film = ctx.matches.named(MatchName.FILM)
            .filter(x -> !x.isPrivate())
            .findFirst().orElse(null);
        if (film == null) return;
        if (ctx.matches.named(MatchName.FILM_TITLE).findAny().isPresent()) return;

        // Find the filepart (path marker) that contains this film match.
        var fpOpt = ctx.markers.stream()
            .filter(mk -> mk.name().equals("path") && mk.covers(film.start(), film.end()))
            .findFirst();
        if (fpOpt.isEmpty()) return;
        var fp = fpOpt.get();

        // Leading hole: text before the film match within the filepart. The
        // match span now includes the 'f' prefix, so use film.start() directly.
        int filmTokenStart = film.start();

        var holes = Holes.compute(
            ctx.input,
            fp.start(),
            filmTokenStart,
            ctx.matches.snapshot(),
            Match::isPrivate,   // ignore private matches
            null,               // no sep splitting — keep continuous text together
            Formatters::cleanup
        );

        if (holes.isEmpty()) return;

        var hole = holes.getFirst();
        var title = hole.value();
        if (title == null || title.isBlank()) return;

        ctx.matches.add(new Match(MatchName.FILM_TITLE, title,
            hole.start, hole.end, hole.raw(), priority(), Set.of(), false));
    }
}
