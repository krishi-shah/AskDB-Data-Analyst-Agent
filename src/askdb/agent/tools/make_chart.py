from __future__ import annotations

from askdb.agent.tools.base import Tool, ToolResult


class MakeChartTool(Tool):
    name = "make_chart"
    description = "Build a chart from the latest successful query result."
    parameters = {
        "chart_type": {"type": "string", "required": False,
                       "description": "bar, line, pie, scatter or table; chosen automatically if omitted"},
    }

    def __init__(self, recommender) -> None:
        self.recommender = recommender

    def execute(self, args: dict, session) -> ToolResult:
        result = session.last_result
        if result is None or result.is_empty():
            return ToolResult.failure("There is no query result to chart yet.")
        chart = self.recommender.recommend(result, (args.get("chart_type") or "").lower())
        spec = chart.render(result)
        return ToolResult(ok=True, content=f"Built a {spec.chart_type} chart: {spec.title}", data=spec)
