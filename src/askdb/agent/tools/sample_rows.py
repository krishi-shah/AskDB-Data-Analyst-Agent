from __future__ import annotations

from askdb.agent.tools.base import Tool, ToolResult


def quote_ident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


class SampleRowsTool(Tool):
    name = "sample_rows"
    description = "Show a few rows of one table to see what the values look like."
    parameters = {
        "table": {"type": "string", "required": True, "description": "table name"},
        "n": {"type": "integer", "required": False, "description": "number of rows, at most 10 (default 5)"},
    }

    def __init__(self, executor, share_samples: bool = True) -> None:
        self.executor = executor
        self.share_samples = share_samples

    def execute(self, args: dict, session) -> ToolResult:
        if not self.share_samples:
            return ToolResult.failure("Sharing sample rows is turned off in Settings. Use search_schema instead.")
        table = session.schema.table(args["table"])
        if table is None:
            names = ", ".join(t.name for t in session.schema.tables)
            return ToolResult.failure(f"No table named '{args['table']}'. Tables: {names}.")
        n = max(1, min(int(args.get("n") or 5), 10))
        result = self.executor.run(f"SELECT * FROM {quote_ident(table.name)} LIMIT {n}", session.data_source)
        return ToolResult(ok=True, content=result.preview_text(n), data=result)
