package ca.yorku.askdb.data;

import java.util.ArrayList;
import java.util.List;

public class QueryResult {
    private final List<String> columns;
    private final List<List<Object>> rows;
    private final boolean truncated;
    private final double elapsedMs;
    private final String sql;

    public QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated, double elapsedMs, String sql) {
        this.columns = List.copyOf(columns);
        this.rows = rows;
        this.truncated = truncated;
        this.elapsedMs = elapsedMs;
        this.sql = sql;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public double getElapsedMs() {
        return elapsedMs;
    }

    public String getSql() {
        return sql;
    }

    public int rowCount() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public List<Object> columnValues(int index) {
        List<Object> values = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            values.add(row.get(index));
        }
        return values;
    }

    public List<Integer> numericColumns() {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            boolean any = false;
            boolean allNumbers = true;
            for (Object v : columnValues(i)) {
                if (v == null) {
                    continue;
                }
                any = true;
                if (!(v instanceof Number)) {
                    allNumbers = false;
                    break;
                }
            }
            if (any && allNumbers) {
                found.add(i);
            }
        }
        return found;
    }

    /** Short text form sent back to the model, never the full result. */
    public String previewText(int maxRows) {
        StringBuilder sb = new StringBuilder();
        sb.append(rowCount()).append(" rows\n").append(String.join(" | ", columns)).append('\n');
        for (int r = 0; r < Math.min(maxRows, rows.size()); r++) {
            List<String> cells = new ArrayList<>();
            for (Object v : rows.get(r)) {
                cells.add(format(v));
            }
            sb.append(String.join(" | ", cells));
            if (r < Math.min(maxRows, rows.size()) - 1) {
                sb.append('\n');
            }
        }
        if (rowCount() > maxRows) {
            sb.append("\n... ").append(rowCount() - maxRows).append(" more rows");
        }
        return sb.toString();
    }

    public static String format(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Double d && Double.isFinite(d)) {
            return java.math.BigDecimal.valueOf(d).toPlainString();
        }
        return String.valueOf(v);
    }
}
