"""ResponseParser turns model text into typed objects. Bad output raises ParseError."""

from __future__ import annotations

import json
import re
from typing import Any

from askdb.agent.models import AgentAction, ClarificationRequest, Insight, QueryPlan

CHART_HINTS = {"bar", "line", "pie", "scatter", "table"}


class ParseError(ValueError):
    pass


def extract_json(text: str) -> dict[str, Any]:
    """Read the first JSON object in text, tolerating code fences and leading prose."""
    cleaned = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.MULTILINE).strip()
    start = cleaned.find("{")
    if start == -1:
        raise ParseError("The model reply contained no JSON object.")
    try:
        obj, _ = json.JSONDecoder().raw_decode(cleaned[start:])
    except json.JSONDecodeError as exc:
        raise ParseError(f"The model reply was not valid JSON: {exc.msg}") from exc
    if not isinstance(obj, dict):
        raise ParseError("The model reply was not a JSON object.")
    return obj


class ResponseParser:
    def parse_plan(self, text: str) -> QueryPlan:
        data = extract_json(text)
        category = str(data.get("category", "query")).strip().lower()
        if category not in ("query", "destructive", "unanswerable"):
            category = "query"
        hint = str(data.get("chart_hint", "") or "").strip().lower()
        clar = None
        raw = data.get("clarification")
        if isinstance(raw, dict):
            options = [str(o).strip() for o in raw.get("options", []) if str(o).strip()]
            question = str(raw.get("question", "")).strip()
            if question and len(options) >= 2:
                clar = ClarificationRequest(question=question, options=options[:4])
        tables = data.get("tables") or []
        return QueryPlan(
            category=category,
            approach=str(data.get("approach", "")),
            tables=[str(t) for t in tables] if isinstance(tables, list) else [],
            chart_hint=hint if hint in CHART_HINTS else "",
            ambiguous=bool(data.get("ambiguous", False)) and clar is not None,
            clarification=clar,
            reason=str(data.get("reason", "")),
        )

    def parse_action(self, text: str) -> AgentAction:
        data = extract_json(text)
        kind = str(data.get("action", "")).strip().lower()
        thought = str(data.get("thought", ""))
        if kind == "final":
            return AgentAction(kind="final", thought=thought, answer=str(data.get("answer", "")))
        tool = str(data.get("tool", "")).strip()
        if kind in ("tool", "") and tool:
            args = data.get("args") or {}
            if not isinstance(args, dict):
                raise ParseError("Tool args must be a JSON object.")
            return AgentAction(kind="tool", tool=tool, args=args, thought=thought)
        if kind and kind not in ("tool", "final"):
            args = data.get("args") or {}
            return AgentAction(kind="tool", tool=kind, args=args if isinstance(args, dict) else {}, thought=thought)
        raise ParseError("The reply must have action 'tool' with a tool name, or action 'final'.")

    def parse_insight(self, text: str) -> Insight:
        try:
            data = extract_json(text)
            summary = data.get("summary") or data.get("text") or ""
        except ParseError:
            summary = text
        summary = str(summary).strip()
        if not summary:
            raise ParseError("The summary was empty.")
        return Insight(text=summary)

    def parse_descriptions(self, text: str) -> list[dict]:
        data = extract_json(text)
        tables = data.get("tables", [])
        if not isinstance(tables, list):
            raise ParseError("Expected a list of tables.")
        parsed = []
        for item in tables:
            if not isinstance(item, dict) or "name" not in item:
                continue
            cols = item.get("columns") or {}
            if isinstance(cols, list):
                cols = {str(c.get("name")): str(c.get("description", "")) for c in cols if isinstance(c, dict)}
            parsed.append({"name": str(item["name"]), "description": str(item.get("description", "")),
                           "columns": {str(k): str(v) for k, v in cols.items()}})
        return parsed
