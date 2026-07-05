package org.jarvis.lifetracker.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvUtilsTest {

    @Test
    void parseCsvReturnsEmptyListForNullOrBlank() {
        assertThat(CsvUtils.parseCsv(null)).isEmpty();
        assertThat(CsvUtils.parseCsv("")).isEmpty();
        assertThat(CsvUtils.parseCsv("   \n  \n")).isEmpty();
    }

    @Test
    void parseCsvSplitsPlainCommaSeparatedRows() {
        List<List<String>> rows = CsvUtils.parseCsv("date,amount,category\n2026-03-01,15.50,Food\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("2026-03-01", "15.50", "Food");
    }

    @Test
    void parseLineHandlesQuotedFieldWithEmbeddedComma() {
        List<String> fields = CsvUtils.parseLine("\"Platnosc 12,34 PLN Lidl\",HIGH");

        assertThat(fields).containsExactly("Platnosc 12,34 PLN Lidl", "HIGH");
    }

    @Test
    void parseLineHandlesEscapedQuotes() {
        List<String> fields = CsvUtils.parseLine("\"He said \"\"hi\"\"\",ok");

        assertThat(fields).containsExactly("He said \"hi\"", "ok");
    }

    @Test
    void parseCsvSkipsBlankLinesBetweenRows() {
        List<List<String>> rows = CsvUtils.parseCsv("a,b\n\n  \nc,d\n");

        assertThat(rows).hasSize(2);
    }

    @Test
    void escapeQuotesFieldsContainingCommaQuoteOrNewline() {
        assertThat(CsvUtils.escape("plain")).isEqualTo("plain");
        assertThat(CsvUtils.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvUtils.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvUtils.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvUtils.escape(null)).isEqualTo("");
    }

    @Test
    void escapeHandlesNonStringValues() {
        assertThat(CsvUtils.escape(42)).isEqualTo("42");
        assertThat(CsvUtils.escape(12.5)).isEqualTo("12.5");
    }
}
