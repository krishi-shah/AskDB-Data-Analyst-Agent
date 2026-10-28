package ca.yorku.askdb.data;

public class ColumnInfo {
    private final String name;
    private final String type;
    private final boolean primaryKey;
    private final String references;
    private String description = "";

    public ColumnInfo(String name, String type, boolean primaryKey, String references) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.references = references;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    /** "table.column" this column points to, or null. */
    public String getReferences() {
        return references;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }
}
