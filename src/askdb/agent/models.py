"""Typed objects that flow through the agent."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from askdb.data.models import QueryResult


class AnswerStatus(str, Enum):
    ANSWERED = "ANSWERED"
    NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"
    REFUSED = "REFUSED"
    NO_DATA = "NO_DATA"
    FAILED = "FAILED"


@dataclass
class ClarificationRequest:
    question: str
    options: list[str]


@dataclass
class QueryPlan:
    category: str = "query"
    approach: str = ""
    tables: list[str] = field(default_factory=list)
    chart_hint: str = ""
    ambiguous: bool = False
    clarification: ClarificationRequest | None = None
    reason: str = ""

    @property
    def needs_clarification(self) -> bool:
        return self.ambiguous and self.clarification is not None


@dataclass
class AgentAction:
    kind: str
    tool: str = ""
    args: dict[str, Any] = field(default_factory=dict)
    thought: str = ""
    answer: str = ""

    @property
    def is_final(self) -> bool:
        return self.kind == "final"


@dataclass
class Insight:
    text: str


@dataclass
class VerificationReport:
    verified: bool
    checked: list[str] = field(default_factory=list)
    unverified: list[str] = field(default_factory=list)

    def feedback(self) -> str:
        return ("These numbers do not appear in the result or follow from it: "
                + ", ".join(self.unverified) + ". Use only numbers from the result.")


@dataclass
class Observation:
    action: AgentAction
    ok: bool
    content: str


@dataclass
class AgentState:
    question: str
    plan: QueryPlan | None = None
    context: str = ""
    observations: list[Observation] = field(default_factory=list)
    step_count: int = 0
    repair_count: int = 0
    seen_sql: set[str] = field(default_factory=set)
    last_sql: str = ""
    last_error: str = ""
    successful_query: QueryResult | None = None
    successful_sql: str = ""
    clarification_rounds: int = 0
    clarified: list[str] = field(default_factory=list)
    pending_clarification: ClarificationRequest | None = None


@dataclass
class AgentAnswer:
    status: AnswerStatus
    question: str = ""
    message: str = ""
    sql: str = ""
    result: QueryResult | None = None
    chart: Any = None
    insight: Insight | None = None
    verification: VerificationReport | None = None
    clarification: ClarificationRequest | None = None
    steps: list[Observation] = field(default_factory=list)
    history_id: int | None = None
    manual: bool = False
