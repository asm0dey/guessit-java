package io.guessit.cli;

import io.guessit.GuessResult;
import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import io.guessit.Options;
import io.guessit.engine.CompositeTrace;
import io.guessit.engine.DebugTrace;
import io.guessit.engine.PrintTrace;
import io.guessit.engine.Trace;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "guessit-java",
    mixinStandardHelpOptions = true,
    versionProvider = GuessitCli.VersionProvider.class,
    description = "Parse video filenames into structured metadata."
)
public final class GuessitCli implements Callable<Integer> {

    @Parameters(arity = "0..*", description = "Filenames to parse.")
    final
    List<String> filenames = new ArrayList<>();

    @Option(names = {"-t", "--type"}, description = "movie or episode hint.")
    String type;

    @Option(names = {"-n", "--name"}, description = "Override input name.")
    String name;

    @Option(names = {"-Y", "--date-year-first"}) boolean dateYearFirst;
    @Option(names = {"-D", "--date-day-first"})  boolean dateDayFirst;

    @Option(names = {"-L", "--allowed-language"}, arity = "1..*")
    final
    List<String> allowedLanguages = new ArrayList<>();

    @Option(names = {"-C", "--allowed-country"}, arity = "1..*")
    final
    List<String> allowedCountries = new ArrayList<>();

    @Option(names = {"-E", "--episode-prefer-number"})
    boolean episodePreferNumber;

    @Option(names = {"-T", "--expected-title"}, arity = "1..*")
    final
    List<String> expectedTitles = new ArrayList<>();

    @Option(names = {"-G", "--expected-group"}, arity = "1..*")
    final
    List<String> expectedGroups = new ArrayList<>();

    @Option(names = "--excludes", arity = "1..*")
    final
    List<String> excludes = new ArrayList<>();

    @Option(names = "--includes", arity = "1..*")
    final
    List<String> includes = new ArrayList<>();

    @Option(names = {"-c", "--config"}, arity = "1..*")
    final
    List<Path> configs = new ArrayList<>();

    @Option(names = "--no-user-config")    boolean noUserConfig;
    @Option(names = "--no-default-config") boolean noDefaultConfig;

    @Option(names = {"-j", "--json"}) boolean json;
    @Option(names = {"-y", "--yaml"}) boolean yaml;
    @Option(names = {"-v", "--verbose"}) boolean verbose;

    @Option(names = "--debug",         description = "Emit human-readable narration of every parse step.")
    boolean debug;

    @Option(names = "--debug-out",     paramLabel = "PATH",
            description = "Write --debug output to PATH (default: stderr).")
    Path debugOut;

    @Option(names = "--debug-markers", description = "Render an ASCII span view whenever the match set changes (requires --debug).")
    boolean debugMarkers;

    @Option(names = {"-P", "--show-property"})
    String showProperty;

    @Option(names = "--advanced") boolean advanced;

    @Override
    public Integer call() {
        if (filenames.isEmpty()) {
            System.err.println("No input filename provided. See --help.");
            return 2;
        }
        if (debugMarkers && !debug) {
            System.err.println("error: --debug-markers requires --debug");
            return 2;
        }
        var guessit = Guessit.withOptions(buildOptions());
        return runWithTraces(guessit);
    }

    private Integer runWithTraces(Guessit guessit) {
        Writer debugSink = null;
        boolean closeDebugSink = false;
        try {
            DebugTrace debugTrace = null;
            if (debug) {
                if (debugOut != null) {
                    debugSink = Files.newBufferedWriter(debugOut, StandardCharsets.UTF_8);
                    closeDebugSink = true;
                } else {
                    debugSink = new OutputStreamWriter(System.err, StandardCharsets.UTF_8);
                }
                debugTrace = new DebugTrace(debugSink, debugMarkers);
            }
            PrintTrace verboseTrace = verbose ? new PrintTrace(System.out) : null;

            if (verbose && (json || yaml || showProperty != null)) {
                System.err.println("warning: --json/--yaml/--show-property ignored when --verbose is set");
            }

            Trace trace;
            if (verboseTrace != null && debugTrace != null) {
                trace = new CompositeTrace(verboseTrace, debugTrace);
            } else if (verboseTrace != null) {
                trace = verboseTrace;
            } else if (debugTrace != null) {
                trace = debugTrace;
            } else {
                trace = Trace.NOOP;
            }

            for (int i = 0; i < filenames.size(); i++) {
                if (i > 0) {
                    if (verbose) System.out.println();
                    if (debug && debugSink != null) debugSink.append("\n");
                }
                var fn = filenames.get(i);
                var result = guessit.guess(fn, trace);
                if (!verbose) {
                    System.out.println(formatResult(result));
                }
            }
            if (debugSink != null) debugSink.flush();
            return 0;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } finally {
            if (closeDebugSink && debugSink != null) {
                try { debugSink.close(); } catch (IOException ignored) {}
            }
        }
    }

    private Options buildOptions() {
        return OptionsBuilder.options()
            .type(type)
            .name(name)
            .expectedTitle(expectedTitles)
            .expectedGroup(expectedGroups)
            .excludes(excludes)
            .includes(includes)
            .allowedLanguages(allowedLanguages)
            .allowedCountries(allowedCountries)
            .dateYearFirst(dateYearFirst ? Boolean.TRUE : null)
            .dateDayFirst(dateDayFirst ? Boolean.TRUE : null)
            .episodePreferNumber(episodePreferNumber ? Boolean.TRUE : null)
            .configPaths(configs)
            .noUserConfig(noUserConfig)
            .noDefaultConfig(noDefaultConfig)
            .build();
    }

    private String formatResult(GuessResult result) {
        if (showProperty != null) {
            var v = result.toMap().get(showProperty);
            return v == null ? "" : v.toString();
        }
        if (json) return JsonFormatter.format(result);
        if (yaml) return YamlFormatter.format(result);
        return PlainFormatter.format(result);
    }

    static void main(String[] args) {
        System.exit(new CommandLine(new GuessitCli()).execute(args));
    }

    public static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"guessit-java 0.1.0-SNAPSHOT"};
        }
    }
}
