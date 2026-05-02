package io.guessit.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommonsCsvSmokeTest {

    @Test
    void parsesHeaderAndRow() throws Exception {
        var csv = "alpha2,name\nUS,United States\n";
        try (var parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
            var records = parser.getRecords();
            assertEquals(1, records.size());
            assertEquals("US", records.get(0).get("alpha2"));
            assertEquals("United States", records.get(0).get("name"));
        }
    }

    @Test
    void parsesQuotedComma() throws Exception {
        var csv = "name\n\"Foo, Inc.\"\n";
        try (var parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
            var records = parser.getRecords();
            assertEquals(List.of("Foo, Inc."), List.of(records.get(0).get("name")));
        }
    }
}
