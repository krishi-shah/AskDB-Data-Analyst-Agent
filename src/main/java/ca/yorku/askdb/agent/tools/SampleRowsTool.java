package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.data.QueryExecutor;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.TableInfo;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class SampleRowsTool implements Tool {
    private final QueryExecutor executor;
    private final BooleanSupplier shareSamples;

    public SampleRowsTool(QueryExecutor executor, BooleanSupplier shareSamples) {
        this.executor = executor;
        this.shareSamples = shareSamples;
    }

    @Override
    public String name() {
        return "sample_rows";
    }

    @Override
    public String description() {
        return "Show a few rows of one table to see what the values look like.";
    }

    @Override
    public Map<String, Param> parameters() {
        return SearchSchemaTool.ordered(Map.of(
                "table", new Param("string", true, "table name"),
                "n", new Param("integer", false, "number of rows, at most 10 (default 5)")), "table", "n");
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Session session) {
        if (!shareSamples.getAsBoolean()) {
            return ToolResult.failure("Sharing sample rows is turned off in Settings. Use search_schema instead.");
        }
        String name = (String) args.get("table");
        TableInfo table = session.getSchema().table(name);
        if (table == null) {
            String names = session.getSchema().getTables().stream().map(TableInfo::getName)
                    .collect(Collectors.joining(", "));
            return ToolResult.failure("No table named '" + name + "'. Tables: " + names + ".");
        }
        int n = Tool.intArg(args, "n", 5, 1, 10);
        QueryResult result = executor.run("SELECT * FROM " + Tool.quoteIdent(table.getName()) + " LIMIT " + n,
                session.getDataSource());
        return ToolResult.success(result.previewText(n), result);
    }
}
