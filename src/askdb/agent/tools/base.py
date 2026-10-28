from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any


@dataclass
class ToolResult:
    ok: bool
    content: str
    data: Any = None
    error: str = ""

    @classmethod
    def failure(cls, error: str) -> "ToolResult":
        return cls(ok=False, content=f"Error: {error}", error=error)


class Tool(ABC):
    """One action the agent may take. parameters maps an argument name to its type, requirement and meaning."""

    name = "tool"
    description = ""
    parameters: dict[str, dict] = {}

    def spec(self) -> dict:
        return {
            "name": self.name,
            "description": self.description,
            "args": {k: f"{v['type']}{'' if v.get('required') else ', optional'}: {v['description']}"
                     for k, v in self.parameters.items()},
        }

    @abstractmethod
    def execute(self, args: dict, session) -> ToolResult:
        ...
