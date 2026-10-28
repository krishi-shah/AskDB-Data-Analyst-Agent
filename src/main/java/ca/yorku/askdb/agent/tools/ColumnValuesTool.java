package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.QueryExecutor;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.TableInfo;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class ColumnValuesTool implements Tool {
    private final QueryExecutor executor;
    private final BooleanSupplier shareSamples;

    public ColumnValuesTool(QueryExecutor executor, BooleanSupplier shareSamples) {
        this.executor = executor;
        this.shareSamples = shareSamples;
    }

    @Override
    public String name() {
        return "column_values";
    }

    @Override
    public String description() {
        return "List the distinct stored values of a column with counts. Use it before filtering on "
                + "text so the filter matches real values (spelling, case, date format).";
    }

    @Override
    public Map<String, Param> parameters() {
        return SearchSchemaTool.ordered(Map.of(
                "table", new Param("string", true, "table name"),
                "column", new Param("string", true, "column name"),
                "limit", new Param("integer", false, "most values to list (default 20)")),
                "table", "column", "limit");
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Session session) {
        if (!shareSamples.getAsBoolean()) {
            return ToolResult.failure("Sharing data values is turned off in Settings.");
        }
        String tableName = (String) args.get("table");
        TableInfo table = session.getSchema().table(tableName);
        if (table == null) {
            return ToolResult.failure("No table named '" + tableName + "'.");
        }
        String columnName = (String) args.get("column");
        ColumnInfo column = table.column(columnName);
        if (column == null) {
            String cols = table.getColumns().stream().map(ColumnInfo::getName).collect(Collectors.joining(", "));
            return ToolResult.failure("Table " + table.getName() + " has no column '" + columnName
                    + "'. Columns: " + cols + ".");
        }
        int limit = Tool.intArg(args, "limit", 20, 1, 50);
        String col = Tool.quoteIdent(column.getName());
        String sql = "SELECT " + col + ", COUNT(*) AS n FROM " + Tool.quoteIdent(table.getName())
                + " GROUP BY " + col + " ORDER BY n DESC LIMIT " + limit;
        QueryResult result = executor.run(sql, session.getDataSource());
        return ToolResult.success(result.previewText(limit), result);
    }
}
