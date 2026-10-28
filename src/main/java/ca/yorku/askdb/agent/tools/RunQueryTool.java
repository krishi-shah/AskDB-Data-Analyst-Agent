package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.data.DatasetException;
import ca.yorku.askdb.data.QueryExecutor;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.safety.SqlValidator;
import ca.yorku.askdb.safety.ValidationResult;
import java.util.Map;

public class RunQueryTool implements Tool {
    private final SqlValidator validator;
    private final QueryExecutor executor;

    public RunQueryTool(SqlValidator validator, QueryExecutor executor) {
        this.validator = validator;
        this.executor = executor;
    }

    @Override
    public String name() {
        return "run_query";
    }

    @Override
    public String description() {
        return "Run one read-only SQLite SELECT statement and see the first rows of the result.";
    }

    @Override
    public Map<String, Param> parameters() {
        return Map.of("sql", new Param("string", true, "a single SELECT or WITH query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Session session) {
        String sql = ((String) args.get("sql")).strip();
        ValidationResult check = validator.validate(sql);
        if (!check.ok()) {
            return ToolResult.failure("Query rejected by the safety guard (" + check.rule() + "): " + check.reason());
        }
        QueryResult raw;
        try {
            raw = executor.run(check.sql(), session.getDataSource());
        } catch (DatasetException.QueryFailed e) {
            return ToolResult.failure("SQLite error: " + e.getMessage());
        }
        QueryResult result = new QueryResult(raw.getColumns(), raw.getRows(), raw.isTruncated(), raw.getElapsedMs(),
                sql.replaceAll(";+\\s*$", "").strip());
        session.setLastResult(result);
        if (result.isEmpty()) {
            return ToolResult.success("The query ran but returned 0 rows.", result);
        }
        return ToolResult.success(result.previewText(10), result);
    }
}
