from __future__ import annotations

from askdb.agent.tools.base import Tool, ToolResult
from askdb.agent.tools.sample_rows import quote_ident


class ColumnValuesTool(Tool):
    name = "column_values"
    description = ("List the distinct stored values of a column with counts. Use it before filtering on "
                   "text so the filter matches real values (spelling, case, date format).")
    parameters = {
        "table": {"type": "string", "required": True, "description": "table name"},
        "column": {"type": "string", "required": True, "description": "column name"},
        "limit": {"type": "integer", "required": False, "description": "most values to list (default 20)"},
    }

    def __init__(self, executor, share_samples: bool = True) -> None:
        self.executor = executor
        self.share_samples = share_samples

    def execute(self, args: dict, session) -> ToolResult:
        if not self.share_samples:
            return ToolResult.failure("Sharing data values is turned off in Settings.")
        table = session.schema.table(args["table"])
        if table is None:
            return ToolResult.failure(f"No table named '{args['table']}'.")
        column = table.column(args["column"])
        if column is None:
            cols = ", ".join(c.name for c in table.columns)
            return ToolResult.failure(f"Table {table.name} has no column '{args['column']}'. Columns: {cols}.")
        limit = max(1, min(int(args.get("limit") or 20), 50))
        col, tab = quote_ident(column.name), quote_ident(table.name)
        sql = f"SELECT {col}, COUNT(*) AS n FROM {tab} GROUP BY {col} ORDER BY n DESC LIMIT {limit}"
        result = self.executor.run(sql, session.data_source)
        return ToolResult(ok=True, content=result.preview_text(limit), data=result)
