package io.guessit.cli;

import io.guessit.Guessit;
import io.guessit.OptionsBuilder;
import io.guessit.engine.PrintTrace;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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
    List<String> filenames = new ArrayList<>();

    @Option(names = {"-t", "--type"}, description = "movie or episode hint.")
    String type;

    @Option(names = {"-n", "--name"}, description = "Override input name.")
    String name;

    @Option(names = {"-Y", "--date-year-first"}) boolean dateYearFirst;
    @Option(names = {"-D", "--date-day-first"})  boolean dateDayFirst;

    @Option(names = {"-L", "--allowed-language"}, arity = "1..*")
    List<String> allowedLanguages = new ArrayList<>();

    @Option(names = {"-C", "--allowed-country"}, arity = "1..*")
    List<String> allowedCountries = new ArrayList<>();

    @Option(names = {"-E", "--episode-prefer-number"})
    boolean episodePreferNumber;

    @Option(names = {"-T", "--expected-title"}, arity = "1..*")
    List<String> expectedTitles = new ArrayList<>();

    @Option(names = {"-G", "--expected-group"}, arity = "1..*")
    List<String> expectedGroups = new ArrayList<>();

    @Option(names = "--excludes", arity = "1..*")
    List<String> excludes = new ArrayList<>();

    @Option(names = "--includes", arity = "1..*")
    List<String> includes = new ArrayList<>();

    @Option(names = {"-c", "--config"}, arity = "1..*")
    List<Path> configs = new ArrayList<>();

    @Option(names = "--no-user-config")    boolean noUserConfig;
    @Option(names = "--no-default-config") boolean noDefaultConfig;

    @Option(names = {"-j", "--json"}) boolean json;
    @Option(names = {"-y", "--yaml"}) boolean yaml;
    @Option(names = {"-v", "--verbose"}) boolean verbose;

    @Option(names = {"-P", "--show-property"})
    String showProperty;

    @Option(names = "--advanced") boolean advanced;

    @Override
    public Integer call() {
        if (filenames.isEmpty()) {
            System.err.println("No input filename provided. See --help.");
            return 2;
        }
        var opts = OptionsBuilder.options()
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
        var guessit = Guessit.withOptions(opts);

        if (verbose) {
            if (json || yaml || showProperty != null) {
                System.err.println("warning: --json/--yaml/--show-property ignored when --verbose is set");
            }
            var trace = new PrintTrace(System.out);
            for (int i = 0; i < filenames.size(); i++) {
                if (i > 0) System.out.println();
                guessit.guess(filenames.get(i), trace);
            }
            return 0;
        }

        for (var fn : filenames) {
            var result = guessit.guess(fn);
            String output;
            if (showProperty != null) {
                var v = result.toMap().get(showProperty);
                output = v == null ? "" : v.toString();
            } else if (json) {
                output = JsonFormatter.format(result);
            } else if (yaml) {
                output = YamlFormatter.format(result);
            } else {
                output = PlainFormatter.format(result);
            }
            System.out.println(output);
        }
        return 0;
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
