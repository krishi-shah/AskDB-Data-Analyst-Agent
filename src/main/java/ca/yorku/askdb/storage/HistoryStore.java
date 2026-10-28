package ca.yorku.askdb.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Every question and manual query, with its SQL and outcome (F11). */
public class HistoryStore {
    private final Connection conn;

    public HistoryStore(Connection conn) {
        this.conn = conn;
    }

    public synchronized long add(HistoryEntry e) {
        String sql = "INSERT INTO history (dataset, question, sql, status, row_count, summary, manual) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.dataset());
            ps.setString(2, e.question());
            ps.setString(3, e.sql());
            ps.setString(4, e.status());
            ps.setInt(5, e.rowCount());
            ps.setString(6, e.summary());
            ps.setInt(7, e.manual() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not save history: " + ex.getMessage(), ex);
        }
    }

    public synchronized List<HistoryEntry> recent(int limit) {
        return query("SELECT * FROM history ORDER BY id DESC LIMIT ?", limit);
    }

    public synchronized List<HistoryEntry> search(String text, int limit) {
        String like = "%" + text + "%";
        return query("SELECT * FROM history WHERE question LIKE ? OR sql LIKE ? OR summary LIKE ? "
                + "ORDER BY id DESC LIMIT ?", like, like, like, limit);
    }

    public synchronized HistoryEntry get(long id) {
        List<HistoryEntry> found = query("SELECT * FROM history WHERE id = ?", id);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<HistoryEntry> query(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<HistoryEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryEntry(rs.getLong("id"), rs.getString("created_at"), rs.getString("dataset"),
                            rs.getString("question"), rs.getString("sql"), rs.getString("status"),
                            rs.getInt("row_count"), rs.getString("summary"), rs.getInt("manual") != 0));
                }
            }
            return out;
        } catch (SQLException ex) {
            throw new StorageException("Could not read history: " + ex.getMessage(), ex);
        }
    }
}
