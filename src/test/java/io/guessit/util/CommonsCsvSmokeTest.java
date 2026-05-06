package io.guessit.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommonsCsvSmokeTest {

    @Test
    void parsesHeaderAndRow() throws Exception {
        var csv = "alpha2,name\nUS,United States\n";
        try (var parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
            var records = parser.getRecords();
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().get("alpha2")).isEqualTo("US");
            assertThat(records.getFirst().get("name")).isEqualTo("United States");
        }
    }

    @Test
    void parsesQuotedComma() throws Exception {
        var csv = "name\n\"Foo, Inc.\"\n";
        try (var parser = CSVParser.parse(new StringReader(csv),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get())) {
            var records = parser.getRecords();
            assertThat(List.of(records.getFirst().get("name"))).isEqualTo(List.of("Foo, Inc."));
        }
    }
}
