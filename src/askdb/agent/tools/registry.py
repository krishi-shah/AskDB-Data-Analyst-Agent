from __future__ import annotations

from askdb.agent.tools.base import Tool, ToolResult

_TYPES = {"string": str, "integer": int, "list": list}


class ToolRegistry:
    """Registers the tools, describes them to the model, and checks arguments before running one."""

    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        self._tools[tool.name] = tool

    def names(self) -> list[str]:
        return list(self._tools)

    def specs(self, exclude: tuple[str, ...] = ()) -> list[dict]:
        return [t.spec() for name, t in self._tools.items() if name not in exclude]

    def execute(self, name: str, args: dict, session) -> ToolResult:
        tool = self._tools.get(name)
        if tool is None:
            return ToolResult.failure(f"Unknown tool '{name}'. Available tools: {', '.join(self._tools)}.")
        problem = self._check_args(tool, args)
        if problem:
            return ToolResult.failure(problem)
        try:
            return tool.execute(self._coerce(tool, args), session)
        except Exception as exc:  # a tool bug must not crash the loop
            return ToolResult.failure(f"{name} failed: {exc}")

    @staticmethod
    def _check_args(tool: Tool, args: dict) -> str:
        unknown = set(args) - set(tool.parameters)
        if unknown:
            return f"Unknown argument(s) for {tool.name}: {', '.join(sorted(unknown))}."
        for arg, meta in tool.parameters.items():
            if meta.get("required") and (arg not in args or args[arg] in (None, "")):
                return f"{tool.name} needs the argument '{arg}'."
            if arg in args and args[arg] is not None:
                expected = _TYPES[meta["type"]]
                value = args[arg]
                if expected is int and isinstance(value, str) and value.strip().isdigit():
                    continue
                if not isinstance(value, expected) or isinstance(value, bool):
                    return f"Argument '{arg}' of {tool.name} must be a {meta['type']}."
        return ""

    @staticmethod
    def _coerce(tool: Tool, args: dict) -> dict:
        out = dict(args)
        for arg, meta in tool.parameters.items():
            if meta["type"] == "integer" and isinstance(out.get(arg), str):
                out[arg] = int(out[arg])
        return out
