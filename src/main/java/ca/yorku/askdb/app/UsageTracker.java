package ca.yorku.askdb.app;

import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.agent.AgentEventListener;
import ca.yorku.askdb.storage.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Observer that records every model call, so usage survives between CLI commands (F12). */
public class UsageTracker implements AgentEventListener {
    public record ModelUsage(String provider, String model, int calls, int failures, int fallbacks,
                             long inputTokens, long outputTokens, double latencyMs, double costUsd) {
        public double avgLatencyMs() {
            return calls == 0 ? 0 : latencyMs / calls;
        }
    }

    public record UsageSummary(List<ModelUsage> models, String policy) {
        public int totalCalls() {
            return models.stream().mapToInt(ModelUsage::calls).sum();
        }

        public long totalTokens() {
            return models.stream().mapToLong(m -> m.inputTokens() + m.outputTokens()).sum();
        }

        public double totalCost() {
            return models.stream().mapToDouble(ModelUsage::costUsd).sum();
        }
    }

    private final Connection conn;

    public UsageTracker(Connection conn) {
        this.conn = conn;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event.kind().equals("llm_call")) {
            insert(str(event.get("task")), str(event.get("provider")), str(event.get("model")),
                    num(event.get("input_tokens")), num(event.get("output_tokens")),
                    ((Number) event.get("latency_ms")).doubleValue(), ((Number) event.get("cost_usd")).doubleValue(),
                    true, Boolean.TRUE.equals(event.get("fallback")));
        } else if (event.kind().equals("llm_error")) {
            String model = event.message().split(" failed:")[0];
            insert(str(event.get("task")), "", model, 0, 0, 0, 0, false, false);
        }
    }

    private synchronized void insert(String task, String provider, String model, int tin, int tout, double latency,
                                     double cost, boolean ok, boolean fallback) {
        String sql = "INSERT INTO usage (task, provider, model, input_tokens, output_tokens, latency_ms, cost_usd, "
                + "ok, fallback) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, task);
            ps.setString(2, provider);
            ps.setString(3, model);
            ps.setInt(4, tin);
            ps.setInt(5, tout);
            ps.setDouble(6, latency);
            ps.setDouble(7, cost);
            ps.setInt(8, ok ? 1 : 0);
            ps.setInt(9, fallback ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Could not record usage: " + e.getMessage(), e);
        }
    }

    public synchronized UsageSummary summary(String policy) {
        String sql = "SELECT MAX(provider) AS provider, model, SUM(ok) AS calls, SUM(1 - ok) AS failures, "
                + "SUM(fallback) AS fallbacks, SUM(input_tokens) AS tin, SUM(output_tokens) AS tout, "
                + "SUM(CASE WHEN ok = 1 THEN latency_ms ELSE 0 END) AS latency, SUM(cost_usd) AS cost "
                + "FROM usage GROUP BY model ORDER BY calls DESC";
        List<ModelUsage> models = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String provider = rs.getString("provider");
                models.add(new ModelUsage(provider == null ? "" : provider, rs.getString("model"), rs.getInt("calls"),
                        rs.getInt("failures"), rs.getInt("fallbacks"), rs.getLong("tin"), rs.getLong("tout"),
                        rs.getDouble("latency"), rs.getDouble("cost")));
            }
        } catch (SQLException e) {
            throw new StorageException("Could not read usage: " + e.getMessage(), e);
        }
        return new UsageSummary(models, policy);
    }

    public synchronized void reset() {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM usage");
        } catch (SQLException e) {
            throw new StorageException("Could not reset usage: " + e.getMessage(), e);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static int num(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
