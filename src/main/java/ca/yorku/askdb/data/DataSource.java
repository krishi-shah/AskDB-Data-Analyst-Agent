package ca.yorku.askdb.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Target interface of the Adapter pattern for data access: one relational view of SQLite or CSV data. */
public abstract class DataSource implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "askdb-query-timer");
        t.setDaemon(true);
        return t;
    });

    protected Connection conn;

    /** Short name shown to the user. */
    public abstract String label();

    /** Opens a read-only connection. */
    public abstract void connect();

    public Connection connection() {
        if (conn == null) {
            throw new DatasetException.QueryFailed("The dataset is not connected.");
        }
        return conn;
    }

    public List<String> listTables() {
        List<String> names = new ArrayList<>();
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'view') "
                     + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new DatasetException.QueryFailed(e.getMessage(), e);
        }
        return names;
    }

    /** Runs one statement; it is cancelled after timeoutS seconds. maxRows &lt;= 0 means no limit. */
    public QueryResult execute(String sql, double timeoutS, int maxRows) {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        long start = System.nanoTime();
        try (Statement st = connection().createStatement()) {
            ScheduledFuture<?> cancel = TIMER.schedule(() -> {
                timedOut.set(true);
                try {
                    st.cancel();
                } catch (SQLException ignored) {
                    // the statement already finished
                }
            }, (long) (timeoutS * 1000), TimeUnit.MILLISECONDS);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                List<String> columns = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (maxRows > 0 && rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        row.add(normalize(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                return new QueryResult(columns, rows, truncated, elapsed, sql);
            } finally {
                cancel.cancel(false);
            }
        } catch (SQLException e) {
            if (timedOut.get()) {
                throw new DatasetException.QueryTimeout(
                        "The query was cancelled after " + trim(timeoutS) + " seconds.", e);
            }
            throw new DatasetException.QueryFailed(cleanMessage(e), e);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        return value;
    }

    private static String trim(double seconds) {
        return seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds);
    }

    /** sqlite-jdbc prefixes messages with "[SQLITE_ERROR] SQL error or missing database"; keep the useful part. */
    static String cleanMessage(SQLException e) {
        String msg = e.getMessage() == null ? "SQL error" : e.getMessage();
        int open = msg.indexOf('(');
        int close = msg.lastIndexOf(')');
        if (msg.startsWith("[SQLITE_") && open >= 0 && close > open) {
            return msg.substring(open + 1, close);
        }
        return msg;
    }

    @Override
    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // closing a read-only connection cannot lose data
            }
            conn = null;
        }
    }
}
