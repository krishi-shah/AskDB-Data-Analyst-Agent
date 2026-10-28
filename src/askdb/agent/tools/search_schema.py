from __future__ import annotations

from askdb.agent.tools.base import Tool, ToolResult


class SearchSchemaTool(Tool):
    name = "search_schema"
    description = "Find the tables and columns most related to some words. Returns names, types and descriptions."
    parameters = {
        "query": {"type": "string", "required": True, "description": "words to look for, e.g. 'revenue by month'"},
        "k": {"type": "integer", "required": False, "description": "how many matches (default 6)"},
    }

    def execute(self, args: dict, session) -> ToolResult:
        k = max(1, min(int(args.get("k") or 6), 15))
        if session.index is None:
            return ToolResult(ok=True, content=session.schema.to_text())
        hits = session.index.search(args["query"], k)
        tables = sorted({h.table for h in hits})
        text = "Matches:\n" + "\n".join(f"- {h.text}" for h in hits)
        text += "\n\nFull columns of matched tables:\n" + session.schema.to_text(tables)
        return ToolResult(ok=True, content=text, data=hits)
