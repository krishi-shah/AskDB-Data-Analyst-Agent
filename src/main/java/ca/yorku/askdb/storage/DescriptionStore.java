package ca.yorku.askdb.storage;

import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Caches generated descriptions per dataset and keeps the analyst's edits. */
public class DescriptionStore {
    private final Connection conn;

    public DescriptionStore(Connection conn) {
        this.conn = conn;
    }

    public synchronized void saveAll(String dataset, SchemaInfo schema) {
        String sql = "INSERT INTO descriptions (dataset, entry_id, text, edited) VALUES (?, ?, ?, 0) "
                + "ON CONFLICT(dataset, entry_id) DO UPDATE SET text = excluded.text WHERE edited = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (TableInfo table : schema.getTables()) {
                put(ps, dataset, table.getName(), table.getDescription());
                for (ColumnInfo col : table.getColumns()) {
                    put(ps, dataset, table.getName() + "." + col.getName(), col.getDescription());
                }
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new StorageException("Could not save descriptions: " + e.getMessage(), e);
        }
    }

    private static void put(PreparedStatement ps, String dataset, String entryId, String text) throws SQLException {
        ps.setString(1, dataset);
        ps.setString(2, entryId);
        ps.setString(3, text);
        ps.addBatch();
    }

    public synchronized void saveEdit(String dataset, String entryId, String text) {
        String sql = "INSERT INTO descriptions (dataset, entry_id, text, edited) VALUES (?, ?, ?, 1) "
                + "ON CONFLICT(dataset, entry_id) DO UPDATE SET text = excluded.text, edited = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dataset);
            ps.setString(2, entryId);
            ps.setString(3, text);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Could not save the description: " + e.getMessage(), e);
        }
    }

    public synchronized void apply(String dataset, SchemaInfo schema) {
        Map<String, String> texts = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT entry_id, text FROM descriptions WHERE dataset = ?")) {
            ps.setString(1, dataset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    texts.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Could not read descriptions: " + e.getMessage(), e);
        }
        for (TableInfo table : schema.getTables()) {
            table.setDescription(texts.getOrDefault(table.getName(), table.getDescription()));
            for (ColumnInfo col : table.getColumns()) {
                col.setDescription(texts.getOrDefault(table.getName() + "." + col.getName(), col.getDescription()));
            }
        }
    }
}
