from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

from askdb.agent.events import AgentEvent, AgentEventListener


@dataclass
class ModelUsage:
    provider: str
    model: str
    calls: int = 0
    failures: int = 0
    fallbacks: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: float = 0.0
    cost_usd: float = 0.0

    @property
    def avg_latency_ms(self) -> float:
        return self.latency_ms / self.calls if self.calls else 0.0


@dataclass
class UsageSummary:
    models: list[ModelUsage] = field(default_factory=list)
    policy: str = ""

    @property
    def total_calls(self) -> int:
        return sum(m.calls for m in self.models)

    @property
    def total_tokens(self) -> int:
        return sum(m.input_tokens + m.output_tokens for m in self.models)

    @property
    def total_cost(self) -> float:
        return sum(m.cost_usd for m in self.models)


class UsageTracker(AgentEventListener):
    """Observer that records every model call so usage survives between CLI commands."""

    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn

    def on_event(self, event: AgentEvent) -> None:
        if event.kind == "llm_call":
            d = event.data
            self._insert(d["task"], d["provider"], d["model"], d["input_tokens"], d["output_tokens"],
                         d["latency_ms"], d["cost_usd"], ok=True, fallback=d.get("fallback", False))
        elif event.kind == "llm_error":
            model = event.message.split(" failed:")[0]
            self._insert(event.data.get("task", ""), "", model, 0, 0, 0.0, 0.0, ok=False, fallback=False)

    def _insert(self, task, provider, model, tin, tout, latency, cost, ok, fallback) -> None:
        self.conn.execute(
            "INSERT INTO usage (task, provider, model, input_tokens, output_tokens, latency_ms, cost_usd, ok, fallback) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (task, provider, model, tin, tout, latency, cost, int(ok), int(fallback)),
        )
        self.conn.commit()

    def summary(self, policy: str = "") -> UsageSummary:
        rows = self.conn.execute(
            "SELECT MAX(provider) AS provider, model, SUM(ok) AS calls, SUM(1 - ok) AS failures, "
            "SUM(fallback) AS fallbacks, SUM(input_tokens) AS tin, SUM(output_tokens) AS tout, "
            "SUM(CASE WHEN ok = 1 THEN latency_ms ELSE 0 END) AS latency, SUM(cost_usd) AS cost "
            "FROM usage GROUP BY model ORDER BY calls DESC"
        ).fetchall()
        models = [ModelUsage(r["provider"] or "", r["model"], r["calls"], r["failures"], r["fallbacks"], r["tin"],
                             r["tout"], r["latency"], r["cost"]) for r in rows]
        return UsageSummary(models=models, policy=policy)

    def reset(self) -> None:
        self.conn.execute("DELETE FROM usage")
        self.conn.commit()
