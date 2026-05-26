package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import io.guessit.engine.*;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

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

    public static final String EXTRACTOR_NAME = "film";
    private static final String GRP_N = "n";
    private static final String MARKER_PATH = "path";

    private static final Pattern PATTERN = buildPattern();

    private static Pattern buildPattern() {
        var sift = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromAnywhere().character('f')
                .then().namedCapture(capture(GRP_N, between(1, 2).digits()));

        return Pattern.compile(sift.shake());
    }

    @Override
    public String name() {
        return EXTRACTOR_NAME;
    }

    @Override
    public String description() {
        return "film number (Film 1, Movie 2, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);

        while (m.find()) {
            var head = new Match(MatchName.FILM, null, m.start(), m.end(), m.group(), priority(), Set.of(), false);

            if (seps.test(head)) {
                int v = Integer.parseInt(m.group(GRP_N));
                ctx.matches.add(new Match(MatchName.FILM, v,
                        m.start(), m.end(), m.group(), priority(), Set.of(), false));
            }
        }
    }

    @Override
    public void postProcess(ParseContext ctx) {
        if (ctx.matches.named(MatchName.FILM_TITLE).findAny().isPresent()) {
            return;
        }

        ctx.matches.named(MatchName.FILM)
                .filter(x -> !x.isPrivate())
                .findFirst()
                .flatMap(film -> extractFilmTitle(ctx, film))
                .ifPresent(ctx.matches::add);
    }

    private Optional<Match> extractFilmTitle(ParseContext ctx, Match film) {
        return ctx.markers.stream()
                .filter(mk -> mk.name().equals(MARKER_PATH) && mk.covers(film.start(), film.end()))
                .findFirst()
                .flatMap(fp -> {
                    var holes = Holes.compute(
                            ctx.input,
                            fp.start(),
                            film.start(),
                            ctx.matches.snapshot(),
                            Match::isPrivate,
                            null,
                            Formatters::cleanup
                    );

                    if (holes.isEmpty()) {
                        return Optional.empty();
                    }

                    var hole = holes.getFirst();
                    var title = hole.value();

                    if (title == null || title.isBlank()) {
                        return Optional.empty();
                    }

                    return Optional.of(new Match(MatchName.FILM_TITLE, title,
                            hole.start, hole.end, hole.raw(), priority(), Set.of(), false));
                });
    }
}