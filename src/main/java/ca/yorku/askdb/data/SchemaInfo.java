package ca.yorku.askdb.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SchemaInfo {
    private final List<TableInfo> tables;
    private final String sourceLabel;

    public SchemaInfo(List<TableInfo> tables, String sourceLabel) {
        this.tables = List.copyOf(tables);
        this.sourceLabel = sourceLabel;
    }

    public List<TableInfo> getTables() {
        return tables;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public TableInfo table(String name) {
        return tables.stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public String summary() {
        long rows = tables.stream().mapToLong(TableInfo::getRowCount).sum();
        return tables.size() + " tables, " + rows + " rows";
    }

    public String toText() {
        return toText(null);
    }

    /** Compact schema text for prompts; only the named tables if a list is given. */
    public String toText(Collection<String> only) {
        Set<String> wanted = only == null || only.isEmpty() ? null
                : only.stream().map(String::toLowerCase).collect(Collectors.toSet());
        List<String> lines = new ArrayList<>();
        for (TableInfo table : tables) {
            if (wanted != null && !wanted.contains(table.getName().toLowerCase())) {
                continue;
            }
            String head = "TABLE " + table.getName() + " (" + table.getRowCount() + " rows)";
            if (!table.getDescription().isEmpty()) {
                head += " -- " + table.getDescription();
            }
            lines.add(head);
            for (ColumnInfo col : table.getColumns()) {
                List<String> extra = new ArrayList<>();
                if (col.isPrimaryKey()) {
                    extra.add("PK");
                }
                if (col.getReferences() != null) {
                    extra.add("FK -> " + col.getReferences());
                }
                if (!col.getDescription().isEmpty()) {
                    extra.add(col.getDescription());
                }
                String suffix = extra.isEmpty() ? "" : " -- " + String.join("; ", extra);
                lines.add("  " + col.getName() + " " + col.getType() + suffix);
            }
        }
        return String.join("\n", lines);
    }
}
