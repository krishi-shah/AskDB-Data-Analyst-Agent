package ca.yorku.askdb.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads tables, columns, keys, row counts, and sample rows. */
public class SchemaReader {
    private final int sampleSize;

    public SchemaReader() {
        this(5);
    }

    public SchemaReader(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    public SchemaInfo read(DataSource source) {
        Connection c = source.connection();
        List<TableInfo> tables = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            for (String name : source.listTables()) {
                String quoted = CsvDataSource.quote(name);
                Map<String, String> fks = new HashMap<>();
                try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(" + quoted + ")")) {
                    while (rs.next()) {
                        fks.put(rs.getString(4), rs.getString(3) + "." + rs.getString(5));
                    }
                }
                List<ColumnInfo> columns = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + quoted + ")")) {
                    while (rs.next()) {
                        String col = rs.getString(2);
                        String type = rs.getString(3);
                        columns.add(new ColumnInfo(col, type == null || type.isEmpty() ? "ANY" : type,
                                rs.getInt(6) != 0, fks.get(col)));
                    }
                }
                long count;
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quoted)) {
                    count = rs.next() ? rs.getLong(1) : 0;
                }
                QueryResult sample = source.execute("SELECT * FROM " + quoted + " LIMIT " + sampleSize, 10, 0);
                tables.add(new TableInfo(name, columns, count, sample.getRows()));
            }
        } catch (SQLException e) {
            throw new DatasetException.ImportFailed("The schema could not be read: " + e.getMessage(), e);
        }
        return new SchemaInfo(tables, source.label());
    }
}
