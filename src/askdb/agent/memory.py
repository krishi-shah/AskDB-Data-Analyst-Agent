from __future__ import annotations

from dataclasses import asdict, dataclass


@dataclass
class Turn:
    question: str
    sql: str
    result_summary: str
    answer: str = ""


class ConversationMemory:
    """Bounded list of recent turns used to resolve follow-up questions (F10)."""

    def __init__(self, max_turns: int = 5) -> None:
        self.max_turns = max_turns
        self._turns: list[Turn] = []

    @property
    def turns(self) -> list[Turn]:
        return list(self._turns)

    def add_turn(self, turn: Turn) -> None:
        self._turns.append(turn)
        if len(self._turns) > self.max_turns:
            self._turns = self._turns[-self.max_turns:]

    def clear(self) -> None:
        self._turns = []

    def is_empty(self) -> bool:
        return not self._turns

    def context_text(self) -> str:
        if not self._turns:
            return ""
        lines = ["Treat words like 'that', 'those', 'now only' or 'instead' as changes to the latest turn."]
        for i, turn in enumerate(self._turns, start=1):
            lines.append(f"Turn {i}: Q: {turn.question}\n  SQL: {turn.sql}\n  Result: {turn.result_summary}"
                         + (f"\n  Answer: {turn.answer}" if turn.answer else ""))
        return "\n".join(lines)

    def to_list(self) -> list[dict]:
        return [asdict(t) for t in self._turns]

    @classmethod
    def from_list(cls, items: list[dict], max_turns: int = 5) -> "ConversationMemory":
        memory = cls(max_turns)
        for item in items:
            memory.add_turn(Turn(**item))
        return memory
