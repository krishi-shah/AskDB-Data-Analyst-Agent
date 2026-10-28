from __future__ import annotations

from askdb.agent.tools.base import Tool, ToolResult
from askdb.data.errors import QueryError


class RunQueryTool(Tool):
    name = "run_query"
    description = "Run one read-only SQLite SELECT statement and see the first rows of the result."
    parameters = {"sql": {"type": "string", "required": True, "description": "a single SELECT or WITH query"}}

    def __init__(self, validator, executor) -> None:
        self.validator = validator
        self.executor = executor

    def execute(self, args: dict, session) -> ToolResult:
        sql = args["sql"].strip()
        check = self.validator.validate(sql)
        if not check.ok:
            return ToolResult.failure(f"Query rejected by the safety guard ({check.rule}): {check.reason}")
        try:
            result = self.executor.run(check.sql, session.data_source)
        except QueryError as exc:
            return ToolResult.failure(f"SQLite error: {exc}")
        result.sql = sql.rstrip(";").strip()
        session.last_result = result
        if result.is_empty():
            return ToolResult(ok=True, content="The query ran but returned 0 rows.", data=result)
        return ToolResult(ok=True, content=result.preview_text(10), data=result)
