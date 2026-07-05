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
        for (String rawLine : csv.split("\r\n|\r|\n")) {
            if (rawLine.isBlank()) {
                continue;
            }
            rows.add(parseLine(rawLine));
        }
        return rows;
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
