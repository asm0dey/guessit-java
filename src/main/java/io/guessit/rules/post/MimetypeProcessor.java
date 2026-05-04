package io.guessit.rules.post;

import io.guessit.engine.Match;
import io.guessit.engine.ParseContext;
import io.guessit.engine.PostPhase.PostProcessor;

import java.net.URLConnection;
import java.util.Locale;
import java.util.Map;

/**
 * Appends a zero-width {@code mimetype} match derived from the file extension.
 *
 * <p>Uses {@link URLConnection#guessContentTypeFromName} for common types and
 * a small overlay map for extensions that the JDK maps incorrectly or not at
 * all (mkv, flv, srt, ass/ssa, idx, sub, nfo, mp4).
 */
public final class MimetypeProcessor implements PostProcessor {

    private static final Map<String, String> OVERLAY = Map.of(
        "mkv",  "video/x-matroska",
        "flv",  "video/x-flv",
        "mp4",  "video/mp4",
        "srt",  "application/x-subrip",
        "ass",  "text/x-ssa",
        "ssa",  "text/x-ssa",
        "idx",  "application/x-idx",
        "sub",  "application/x-subrip",
        "nfo",  "text/x-nfo"
    );

    @Override
    public void process(ParseContext ctx) {
        var lower = ctx.input.toLowerCase(Locale.ROOT);
        var dot = lower.lastIndexOf('.');
        String mime = null;
        if (dot >= 0 && dot < lower.length() - 1) {
            var ext = lower.substring(dot + 1);
            mime = OVERLAY.get(ext);
        }
        if (mime == null) mime = URLConnection.guessContentTypeFromName(ctx.input);
        if (mime == null) return;
        var pos = ctx.input.length();
        ctx.matches.add(Match.of("mimetype", mime, pos, pos, ""));
    }
}
