from __future__ import annotations

from askdb.agent.models import ClarificationRequest
from askdb.agent.tools.base import Tool, ToolResult


class AskUserTool(Tool):
    name = "ask_user"
    description = ("Ask the analyst to choose between 2 to 4 meanings when the question is truly ambiguous. "
                   "Use it only when guessing would likely give the wrong answer.")
    parameters = {
        "question": {"type": "string", "required": True, "description": "the clarifying question"},
        "options": {"type": "list", "required": True, "description": "2 to 4 short choices"},
    }

    def execute(self, args: dict, session) -> ToolResult:
        options = [str(o).strip() for o in args["options"] if str(o).strip()]
        if len(options) < 2:
            return ToolResult.failure("ask_user needs at least two options.")
        request = ClarificationRequest(question=args["question"].strip(), options=options[:4])
        return ToolResult(ok=True, content=f"Asked the analyst: {request.question}", data=request)
