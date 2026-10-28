"""Grounded insight summary (F09): the model writes, InsightVerifier checks every number."""

from __future__ import annotations

import re
from datetime import date

from askdb.agent.models import Insight, VerificationReport
from askdb.agent.prompt_builder import PromptBuilder
from askdb.agent.response_parser import ParseError, ResponseParser
from askdb.data.models import QueryResult
from askdb.llm.provider import LLMError
from askdb.llm.router import ModelRouter

EMPTY_MESSAGE = "The query returned no rows, so there is nothing to summarize."
_NUMBER = re.compile(r"(?<![\w.])[-+]?\$?\d[\d,]*(?:\.\d+)?%?")


class InsightGenerator:
    def __init__(self, router: ModelRouter, builder: PromptBuilder, parser: ResponseParser) -> None:
        self.router = router
        self.builder = builder
        self.parser = parser

    def generate(self, question: str, result: QueryResult, feedback: str = "") -> Insight:
        if result.is_empty():
            return Insight(text=EMPTY_MESSAGE)
        messages = self.builder.build_insight_prompt(question, result, feedback)
        try:
            response = self.router.complete(messages, task="insight", max_tokens=300)
            return self.parser.parse_insight(response.text)
        except (LLMError, ParseError) as exc:
            return Insight(text=f"No summary could be written ({exc}). The result table is shown below.")


def extract_numbers(text: str) -> list[tuple[str, float, int, bool]]:
    """Returns (original text, value, decimals, is_percent) for every number in text."""
    found = []
    for match in _NUMBER.finditer(text):
        raw = match.group(0)
        cleaned = raw.replace(",", "").replace("$", "").rstrip("%")
        try:
            value = float(cleaned)
        except ValueError:
            continue
        decimals = len(cleaned.split(".")[1]) if "." in cleaned else 0
        found.append((raw, value, decimals, raw.endswith("%")))
    return found


class InsightVerifier:
    """Matches every number in the summary to the result within a rounding tolerance."""

    def verify(self, insight: Insight, result: QueryResult, question: str = "") -> VerificationReport:
        candidates = self._candidates(result)
        allowed = {v for _, v, _, _ in extract_numbers(question)}
        checked, unverified = [], []
        for raw, value, decimals, is_percent in extract_numbers(insight.text):
            if value in allowed or self._is_ordinal(value, result) or self._is_year(value):
                checked.append(raw)
                continue
            tol = max(abs(value) * 0.005, 0.5 * 10 ** (-decimals))
            pool = candidates["percent"] + candidates["plain"] if is_percent else candidates["plain"]
            if any(abs(abs(value) - abs(c)) <= tol for c in pool):
                checked.append(raw)
            else:
                unverified.append(raw)
        return VerificationReport(verified=not unverified, checked=checked, unverified=unverified)

    @staticmethod
    def _is_ordinal(value: float, result: QueryResult) -> bool:
        return value == int(value) and 0 <= value <= max(result.row_count, 10)

    @staticmethod
    def _is_year(value: float) -> bool:
        return value == int(value) and 1900 <= value <= date.today().year + 5

    @staticmethod
    def _candidates(result: QueryResult) -> dict[str, list[float]]:
        plain: list[float] = [float(result.row_count)]
        percent: list[float] = []
        for row in result.rows:
            for cell in row:
                if isinstance(cell, (int, float)) and not isinstance(cell, bool):
                    plain.append(float(cell))
                elif isinstance(cell, str):
                    plain.extend(v for _, v, _, _ in extract_numbers(cell))
        for i in result.numeric_columns():
            values = [float(v) for v in result.column_values(i) if v is not None]
            if not values:
                continue
            total = sum(values)
            plain += [total, total / len(values), min(values), max(values), max(values) - min(values)]
            if total:
                percent += [v / total * 100 for v in values]
            head = values[:50]
            for a in head:
                for b in head:
                    if a != b:
                        plain.append(a - b)
                        if a:
                            percent.append((b - a) / abs(a) * 100)
                            plain.append(b / a)
        return {"plain": plain, "percent": percent}
