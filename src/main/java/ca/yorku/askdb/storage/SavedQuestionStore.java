package ca.yorku.askdb.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Named questions the analyst can rerun later (F11). */
public class SavedQuestionStore {
    private final Connection conn;

    public SavedQuestionStore(Connection conn) {
        this.conn = conn;
    }

    /** Saving under an existing name replaces it. */
    public synchronized void save(SavedQuestion item) {
        String sql = "INSERT INTO saved_questions (name, question, sql, dataset) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(name) DO UPDATE SET question = excluded.question, sql = excluded.sql, "
                + "dataset = excluded.dataset";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.name());
            ps.setString(2, item.question());
            ps.setString(3, item.sql());
            ps.setString(4, item.dataset());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Could not save the question: " + e.getMessage(), e);
        }
    }

    public synchronized List<SavedQuestion> list() {
        return query("SELECT * FROM saved_questions ORDER BY name");
    }

    public synchronized SavedQuestion get(String name) {
        List<SavedQuestion> found = query("SELECT * FROM saved_questions WHERE name = ?", name);
        return found.isEmpty() ? null : found.get(0);
    }

    public synchronized boolean delete(String name) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM saved_questions WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StorageException("Could not delete the question: " + e.getMessage(), e);
        }
    }

    private List<SavedQuestion> query(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<SavedQuestion> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SavedQuestion(rs.getString("name"), rs.getString("question"), rs.getString("sql"),
                            rs.getString("dataset"), rs.getString("created_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new StorageException("Could not read saved questions: " + e.getMessage(), e);
        }
    }
}
