package ca.yorku.askdb.data;

/** Bounded, timed execution of SQL that has already passed SqlValidator. */
public class QueryExecutor {
    private final double timeoutS;
    private final int maxRows;

    public QueryExecutor(double timeoutS, int maxRows) {
        this.timeoutS = timeoutS;
        this.maxRows = maxRows;
    }

    public QueryResult run(String sql, DataSource source) {
        return source.execute(sql, timeoutS, maxRows);
    }
}
