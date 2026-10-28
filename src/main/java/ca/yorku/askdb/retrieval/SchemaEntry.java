package ca.yorku.askdb.retrieval;

/** One searchable entry: a table, or one column as "table.column". */
public class SchemaEntry {
    private final String entryId;
    private final String table;
    private String text;
    private double[] vector;
    private double score;

    public SchemaEntry(String entryId, String table, String text) {
        this.entryId = entryId;
        this.table = table;
        this.text = text;
    }

    public String getEntryId() {
        return entryId;
    }

    public String getTable() {
        return table;
    }

    public String getText() {
        return text;
    }

    void setText(String text) {
        this.text = text;
    }

    double[] getVector() {
        return vector;
    }

    void setVector(double[] vector) {
        this.vector = vector;
    }

    public double getScore() {
        return score;
    }

    void setScore(double score) {
        this.score = score;
    }
}
