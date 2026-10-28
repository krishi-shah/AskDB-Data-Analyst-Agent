"""PromptBuilder (Builder pattern): one build method per prompt kind.

Every prompt is assembled from the same parts: a system instruction, the
question, schema or tool context, and the JSON shape the model must return.
"""

from __future__ import annotations

import json

from askdb.agent.models import AgentState
from askdb.data.models import QueryResult, SchemaInfo

SYSTEM_ROLE = (
    "You are AskDB, a careful data analyst working with a read-only SQLite database. "
    "You only reply with one JSON object and nothing else."
)

PLAN_SHAPE = {
    "category": "query | destructive | unanswerable",
    "approach": "one sentence on how to answer",
    "tables": ["table names that are needed"],
    "chart_hint": "bar | line | pie | scatter | table | none",
    "vague_words": ["words in the question whose measure is not stated, such as best, top, or active"],
    "ambiguous": "true if vague_words is not empty, otherwise false",
    "clarification": {"question": "only if ambiguous", "options": ["meaning 1", "meaning 2"]},
    "reason": "why, if destructive or unanswerable",
}

ACTION_SHAPE_TOOL = {"thought": "short reason", "action": "tool", "tool": "run_query",
                     "args": {"sql": "SELECT ... (put every argument the tool needs here)"}}
ACTION_SHAPE_FINAL = {"thought": "short reason", "action": "final", "answer": "one sentence answer"}


class PromptBuilder:
    def __init__(self, share_samples: bool = True) -> None:
        self.share_samples = share_samples
        self._parts: list[str] = []

    def _reset(self) -> "PromptBuilder":
        self._parts = []
        return self

    def _add(self, title: str, body: str) -> "PromptBuilder":
        if body:
            self._parts.append(f"## {title}\n{body}")
        return self

    def _shape(self, *shapes: dict) -> "PromptBuilder":
        text = "\n".join(json.dumps(s, indent=1) for s in shapes)
        return self._add("Reply with JSON shaped like" + (" one of" if len(shapes) > 1 else ""), text)

    def _messages(self, system: str = SYSTEM_ROLE) -> list[dict]:
        user = "\n\n".join(self._parts)
        self._parts = []
        return [{"role": "system", "content": system}, {"role": "user", "content": user}]

    def build_plan_prompt(self, question: str, context: str, hits: list[str]) -> list[dict]:
        return (
            self._reset()
            ._add("Task", "Plan how to answer the question with SQL over the schema below. "
                          "Classify it: 'destructive' if it asks to change or delete data; "
                          "'unanswerable' only if no table or column in the schema relates to it at all; "
                          "otherwise 'query'. A vague word is never a reason for 'unanswerable': if a key "
                          "word has two or more reasonable meanings in this schema (for example 'best' "
                          "could mean most revenue or most orders), use category 'query', set ambiguous "
                          "to true, and give 2 to 4 short options.")
            ._add("Earlier conversation", context)
            ._add("Relevant schema", "\n".join(hits))
            ._add("Question", question)
            ._shape(PLAN_SHAPE)
            ._messages()
        )

    def build_step_prompt(self, state: AgentState, specs: list[dict]) -> list[dict]:
        return (
            self._reset()
            ._add("Task", "Answer the question by calling one tool per reply. Write SQLite SQL. "
                          "Look at the schema first if you are unsure of names. When a run_query result "
                          "answers the question, reply with action 'final'. Never invent numbers. "
                          "After joining a table to its detail rows (one-to-many), do not SUM a column of the "
                          "parent table, because it repeats once per detail row.")
            ._add("Tools", json.dumps(specs, indent=1))
            ._add("Schema (use these exact table and column names)", state.schema_text)
            ._add("Earlier conversation", state.context)
            ._add("Plan", self._plan_text(state))
            ._add("Clarified meaning", "; ".join(state.clarified))
            ._add("Steps so far", self._observations_text(state))
            ._add("Question", state.question)
            ._shape(ACTION_SHAPE_TOOL, ACTION_SHAPE_FINAL)
            ._messages()
        )

    def build_repair_prompt(self, state: AgentState, error: str, specs: list[dict] | None = None) -> list[dict]:
        return (
            self._reset()
            ._add("Task", f"The last query failed (repair attempt {state.repair_count}). Fix it. "
                          "Use search_schema or column_values to check real names and values, "
                          "or call run_query with corrected SQL. Do not repeat the same SQL.")
            ._add("Tools", json.dumps(specs, indent=1) if specs else "")
            ._add("Schema (use these exact table and column names)", state.schema_text)
            ._add("Failed SQL", state.last_sql)
            ._add("Error", error)
            ._add("Plan", self._plan_text(state))
            ._add("Clarified meaning", "; ".join(state.clarified))
            ._add("Steps so far", self._observations_text(state))
            ._add("Question", state.question)
            ._shape(ACTION_SHAPE_TOOL, ACTION_SHAPE_FINAL)
            ._messages()
        )

    def build_insight_prompt(self, question: str, result: QueryResult, feedback: str = "") -> list[dict]:
        return (
            self._reset()
            ._add("Task", "Write a 1 to 3 sentence summary that answers the question from this result, "
                          "naming the most important values (for example the highest and lowest). "
                          "Only use numbers that appear in the result, or totals, averages, shares, "
                          "and percentage changes computed from it. Round to at most 2 decimals.")
            ._add("Correction", feedback)
            ._add("Question", question)
            ._add("Result", result.preview_text(max_rows=50))
            ._shape({"summary": "text"})
            ._messages()
        )

    def build_description_prompt(self, schema: SchemaInfo, table: str = "") -> list[dict]:
        tables = [t for t in schema.tables if not table or t.name == table]
        blocks = []
        for t in tables:
            cols = ", ".join(f"{c.name} {c.type}" for c in t.columns)
            block = f"TABLE {t.name} ({t.row_count} rows): {cols}"
            if self.share_samples and t.sample_rows:
                block += "\nSample rows:\n" + "\n".join(str(r) for r in t.sample_rows[:3])
            blocks.append(block)
        return (
            self._reset()
            ._add("Task", "Write a short plain-English description (under 15 words) for every table "
                          "and every column, so an analyst knows what each holds.")
            ._add("Schema", "\n\n".join(blocks))
            ._shape({"tables": [{"name": "table", "description": "text",
                                 "columns": {"column_name": "description"}}]})
            ._messages()
        )

    @staticmethod
    def _plan_text(state: AgentState) -> str:
        plan = state.plan
        if plan is None:
            return ""
        return f"{plan.approach} (tables: {', '.join(plan.tables) or 'unknown'})"

    @staticmethod
    def _observations_text(state: AgentState) -> str:
        lines = []
        for i, obs in enumerate(state.observations[-6:], start=1):
            call = f"{obs.action.tool}({json.dumps(obs.action.args)})"
            status = "ok" if obs.ok else "error"
            lines.append(f"{i}. {call} -> {status}\n{obs.content[:1500]}")
        return "\n".join(lines)
