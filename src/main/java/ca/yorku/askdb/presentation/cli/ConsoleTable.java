package ca.yorku.askdb.presentation.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Plain text table for the terminal. Long cells are cut to maxWidth characters. */
public class ConsoleTable {
    private final String title;
    private final List<String> headers;
    private final List<List<String>> rows = new ArrayList<>();
    private int maxWidth = 60;

    public ConsoleTable(String title, List<String> headers) {
        this.title = title;
        this.headers = headers;
    }

    public ConsoleTable maxWidth(int width) {
        maxWidth = width;
        return this;
    }

    public void addRow(List<String> cells) {
        rows.add(cells);
    }

    public void print(PrintStream out) {
        int n = headers.size();
        int[] widths = new int[n];
        for (int c = 0; c < n; c++) {
            widths[c] = headers.get(c).length();
        }
        for (List<String> row : rows) {
            for (int c = 0; c < n; c++) {
                widths[c] = Math.max(widths[c], Math.min(maxWidth, cell(row, c).length()));
            }
        }
        if (title != null && !title.isEmpty()) {
            out.println(title);
        }
        String rule = rule(widths);
        out.println(rule);
        out.println(line(headers, widths));
        out.println(rule);
        for (List<String> row : rows) {
            out.println(line(row, widths));
        }
        out.println(rule);
    }

    private String line(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < widths.length; c++) {
            String v = cell(cells, c);
            if (v.length() > widths[c]) {
                v = v.substring(0, Math.max(0, widths[c] - 3)) + "...";
            }
            sb.append(' ').append(v).append(" ".repeat(widths[c] - v.length())).append(" |");
        }
        return sb.toString();
    }

    private static String rule(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append('+');
        }
        return sb.toString();
    }

    private static String cell(List<String> row, int c) {
        if (c >= row.size() || row.get(c) == null) {
            return "";
        }
        return row.get(c).replace('\n', ' ');
    }
}
