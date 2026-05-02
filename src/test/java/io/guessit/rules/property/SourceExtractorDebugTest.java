package io.guessit.rules.property;

import io.guessit.*;
import io.guessit.engine.*;
import io.guessit.config.ConfigLoader;
import io.guessit.rules.Rules;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SourceExtractorDebugTest {
    @Test
    void debugPatterns() {
        // Simulate exactly what SourceExtractor.buildRules + compileRule does
        var section = ConfigLoader.load(Options.defaults()).section("source");
        var ripSuffix = String.valueOf(section.getOrDefault("rip_suffix", "-?(?<other>Rip)"));
        var optRipSuffix = "(?:" + ripSuffix + ")?";
        
        // Blu-ray rule with rip suffix
        String alt = "Blu-?ray|BD|BD[59]|BD25|BD50";
        String src = "(" + alt + ")" + optRipSuffix;  // prefix="" for this rule
        String processed = Abbreviations.dash(src);
        System.out.println("Blu-ray pattern: " + processed);
        
        try {
            Pattern p = Pattern.compile(processed, Pattern.CASE_INSENSITIVE);
            System.out.println("  >> Compiled OK");
            String[] inputs = {"BDRip.mkv", "Movie.2015.BDRip.mkv", "Movie.2015.BluRay.mkv", "BluRay.mkv"};
            for (String in : inputs) {
                var m = p.matcher(in);
                if (m.find()) {
                    System.out.println("  MATCH '" + in + "' -> [" + m.start() + "," + m.end() + ") '" + m.group(0) + "' g1='" + m.group(1) + "'");
                    try { System.out.println("    other='" + m.group("other") + "'"); } catch (Exception e) {}
                } else {
                    System.out.println("  NO MATCH '" + in + "'");
                }
            }
        } catch (Exception e) {
            System.out.println("  >> COMPILE FAILED: " + e.getMessage());
        }
        
        // HDTV rule
        String hdtvAlt = "HD-?TV";
        String hdtvSrc = "(" + hdtvAlt + ")" + optRipSuffix;
        String hdtvProcessed = Abbreviations.dash(hdtvSrc);
        System.out.println("\nHDTV pattern: " + hdtvProcessed);
        try {
            Pattern p = Pattern.compile(hdtvProcessed, Pattern.CASE_INSENSITIVE);
            String[] inputs = {"HDTV.mkv", "Show.S01E02.HDTV.mkv", "HDTVRip.mkv"};
            for (String in : inputs) {
                var m = p.matcher(in);
                if (m.find()) {
                    System.out.println("  MATCH '" + in + "' -> [" + m.start() + "," + m.end() + ") '" + m.group(0) + "'");
                } else {
                    System.out.println("  NO MATCH '" + in + "'");
                }
            }
        } catch (Exception e) {
            System.out.println("  >> COMPILE FAILED: " + e.getMessage());
        }
        
        // WEBRip — WEB rule with rip suffix
        String webAlt = "WEB|WEB-?DL";
        String webSrc = "(" + webAlt + ")" + ripSuffix;  // not optRipSuffix! ripSuffix is mandatory here
        String webProcessed = Abbreviations.dash(webSrc);
        System.out.println("\nWEB pattern: " + webProcessed);
        try {
            Pattern p = Pattern.compile(webProcessed, Pattern.CASE_INSENSITIVE);
            String[] inputs = {"WEBRip.mkv", "Movie.2015.WEBRip.mkv", "WEB-DL.mkv"};
            for (String in : inputs) {
                var m = p.matcher(in);
                if (m.find()) {
                    System.out.println("  MATCH '" + in + "' -> [" + m.start() + "," + m.end() + ") '" + m.group(0) + "' g1='" + m.group(1) + "'");
                    try { System.out.println("    other='" + m.group("other") + "'"); } catch (Exception e) {}
                } else {
                    System.out.println("  NO MATCH '" + in + "'");
                }
            }
        } catch (Exception e) {
            System.out.println("  >> COMPILE FAILED: " + e.getMessage());
        }
        
        // BR Rip rule
        String brAlt = "(?<another>BR)";
        String brSrc = "(" + brAlt + ")" + ripSuffix;
        String brProcessed = Abbreviations.dash(brSrc);
        System.out.println("\nBR pattern: " + brProcessed);
        try {
            Pattern p = Pattern.compile(brProcessed, Pattern.CASE_INSENSITIVE);
            String[] inputs = {"BRRip.mkv", "Movie.2015.BRRip.mkv"};
            for (String in : inputs) {
                var m = p.matcher(in);
                if (m.find()) {
                    System.out.println("  MATCH '" + in + "' -> [" + m.start() + "," + m.end() + ") '" + m.group(0) + "' g1='" + m.group(1) + "'");
                } else {
                    System.out.println("  NO MATCH '" + in + "'");
                }
            }
        } catch (Exception e) {
            System.out.println("  >> COMPILE FAILED: " + e.getMessage());
        }
    }
}
