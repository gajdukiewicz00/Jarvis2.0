package org.jarvis.lifetracker.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC-4180-style CSV parser: handles quoted fields, embedded commas/newlines,
 * and escaped quotes ({@code ""} -> {@code "}). No external dependency needed for the
 * simple bank-export / life-tracker import shapes this service deals with.
 */
public final class CsvUtils {

    private CsvUtils() {
    }

    /** Splits a whole CSV document into rows of fields. Blank lines are skipped. */
    public static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return rows;
        }
        for (String rawLine : splitRowsQuoteAware(csv)) {
            if (rawLine.isBlank()) {
                continue;
            }
            rows.add(parseLine(rawLine));
        }
        return rows;
    }

    /**
     * Splits a raw CSV document into row strings, tracking quote state across the whole
     * document so that {@code \r\n}/{@code \r}/{@code \n} sequences inside a quoted field
     * are kept as part of the row instead of being treated as a row boundary. Field-level
     * parsing (including escaped-quote handling) is left to {@link #parseLine(String)}.
     */
    private static List<String> splitRowsQuoteAware(String csv) {
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        boolean inQuotes = false;
        int length = csv.length();
        for (int i = 0; i < length; i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                currentLine.append(c);
            } else if (!inQuotes && (c == '\r' || c == '\n')) {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
                if (c == '\r' && i + 1 < length && csv.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                currentLine.append(c);
            }
        }
        lines.add(currentLine.toString());
        return lines;
    }

    /** Splits a single CSV line into fields, respecting quoted commas and escaped quotes. */
    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** Escapes a single CSV field, quoting it only when it contains a comma, quote, or newline. */
    public static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
