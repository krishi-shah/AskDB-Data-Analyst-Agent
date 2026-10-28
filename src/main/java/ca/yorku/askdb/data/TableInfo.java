package ca.yorku.askdb.data;

import java.util.List;

public class TableInfo {
    private final String name;
    private final List<ColumnInfo> columns;
    private final long rowCount;
    private final List<List<Object>> sampleRows;
    private String description = "";

    public TableInfo(String name, List<ColumnInfo> columns, long rowCount, List<List<Object>> sampleRows) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.rowCount = rowCount;
        this.sampleRows = sampleRows;
    }

    public String getName() {
        return name;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public long getRowCount() {
        return rowCount;
    }

    public List<List<Object>> getSampleRows() {
        return sampleRows;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public ColumnInfo column(String columnName) {
        return columns.stream().filter(c -> c.getName().equalsIgnoreCase(columnName)).findFirst().orElse(null);
    }
}
