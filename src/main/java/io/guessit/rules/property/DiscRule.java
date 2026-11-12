package io.guessit.rules.property;

import com.mirkoddd.sift.core.SiftGlobalFlag;
import io.guessit.engine.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.mirkoddd.sift.core.Sift.*;
import static com.mirkoddd.sift.core.SiftPatterns.*;

/**
 * Extracts {@code disc} (multi-disc release indices: "Disc 1", "DVD 2",
 * "BD3", …).
 *
 * <p>The post-pass also fixes up matches that {@link SeasonEpisodeExtractor}
 * recognised via the {@code D} marker (e.g. "S01D02") — those get tagged
 * {@code disc-marker} as episodes and are renamed here once the dust has
 * settled. Doing the rename in this rule keeps disc-related logic in one
 * place rather than scattered across the season/episode extractor.
 */
public final class DiscRule implements Extractor {

    public static final String EXTRACTOR_NAME = "disc";
    public static final String TAG_DISC_MARKER = "disc-marker";

    private static final int PRIORITY = 1000;
    private static final String GRP_VAL = "val";

    private static final Pattern PATTERN = buildDiscPattern();

    private static Pattern buildDiscPattern() {
        var prefixes = anyOf(
                literal("disc"),
                literal("dvd"),
                literal("vcd"),
                literal("bd"),
                literal("brd"),
                literal("bluray")
        );

        var separatorChars = anyOf(literal(" "), literal("."), literal("_"), literal("-"));
        var separators = zeroOrMore().of(separatorChars);

        var sift = filteringWith(SiftGlobalFlag.CASE_INSENSITIVE)
                .fromWordBoundary()
                .followedBy(List.of(prefixes, separators))
                .then().namedCapture(capture(GRP_VAL, oneOrMore().digits()))
                .wordBoundary();

        return Pattern.compile(sift.shake());
    }

    @Override
    public String name() {
        return EXTRACTOR_NAME;
    }

    @Override
    public String description() {
        return "disc number (Disc 1, Disc 2, …)";
    }

    @Override
    public void extract(ParseContext ctx) {
        var input = ctx.input;
        var seps = Validators.sepsSurround(input);
        var m = PATTERN.matcher(input);

        while (m.find()) {
            var head = new Match(MatchName.DISC, null, m.start(), m.end(), m.group(), PRIORITY, Set.of(), false);

            if (seps.test(head)) {
                int v = Integer.parseInt(m.group(GRP_VAL));
                ctx.matches.add(new Match(MatchName.DISC, v, m.start(GRP_VAL), m.end(GRP_VAL),
                        m.group(GRP_VAL), PRIORITY, Set.of(), false));
            }
        }
    }

    /** Mirror Python RenameToDiscMatch: episodes from chains with `D` marker become discs. */
    @Override
    public void postProcess(ParseContext ctx) {
        var marked = ctx.matches.named(MatchName.EPISODE)
                .filter(m -> m.tags().contains(TAG_DISC_MARKER))
                .toList();

        if (marked.isEmpty()) return;

        var renamed = marked.stream()
                .map(m -> {
                    var newTags = new HashSet<>(m.tags());
                    newTags.remove(TAG_DISC_MARKER);
                    return new Match(MatchName.DISC, m.value(), m.start(), m.end(), m.raw(), m.priority(), newTags, m.isPrivate());
                })
                .toList();

        marked.forEach(ctx.matches::remove);
        renamed.forEach(ctx.matches::add);
    }
}