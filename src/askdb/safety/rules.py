"""SQL safety rules arranged with the Decorator pattern.

Each SqlRuleDecorator adds one check, then calls the rule it wraps with the
original or rewritten SQL. The first failure stops the chain.
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class ValidationResult:
    ok: bool
    sql: str
    reason: str = ""
    rule: str = ""

    @classmethod
    def passed(cls, sql: str) -> "ValidationResult":
        return cls(ok=True, sql=sql)

    @classmethod
    def failed(cls, sql: str, rule: str, reason: str) -> "ValidationResult":
        return cls(ok=False, sql=sql, reason=reason, rule=rule)


def strip_literals_and_comments(sql: str) -> str:
    """Blank out comments, string literals, and quoted identifiers so keywords inside them are ignored."""
    out = []
    i, n = 0, len(sql)
    while i < n:
        ch = sql[i]
        if sql.startswith("--", i):
            end = sql.find("\n", i)
            i = n if end == -1 else end
            out.append(" ")
        elif sql.startswith("/*", i):
            end = sql.find("*/", i + 2)
            i = n if end == -1 else end + 2
            out.append(" ")
        elif ch in ("'", '"', "`", "["):
            close = "]" if ch == "[" else ch
            j = i + 1
            while j < n:
                if sql[j] == close:
                    if close != "]" and j + 1 < n and sql[j + 1] == close:
                        j += 2
                        continue
                    break
                j += 1
            out.append("''" if ch == "'" else '""')
            i = j + 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


class SqlRule(ABC):
    """Component: checks a SQL statement."""

    name = "rule"

    @abstractmethod
    def check(self, sql: str) -> ValidationResult:
        ...


class PassThroughRule(SqlRule):
    """Innermost rule of the chain. Accepts whatever reached it."""

    name = "pass"

    def check(self, sql: str) -> ValidationResult:
        return ValidationResult.passed(sql)


class SqlRuleDecorator(SqlRule):
    """Decorator: applies its own check, then delegates to the wrapped rule."""

    def __init__(self, wrapped: SqlRule) -> None:
        self._wrapped = wrapped

    def check(self, sql: str) -> ValidationResult:
        own = self._apply(sql)
        if not own.ok:
            return own
        return self._wrapped.check(own.sql)

    @abstractmethod
    def _apply(self, sql: str) -> ValidationResult:
        ...


class SingleStatementRule(SqlRuleDecorator):
    name = "single_statement"

    def _apply(self, sql: str) -> ValidationResult:
        text = sql.strip().rstrip(";").strip()
        if not text:
            return ValidationResult.failed(sql, self.name, "The SQL statement is empty.")
        if ";" in strip_literals_and_comments(text):
            return ValidationResult.failed(sql, self.name, "Only one SQL statement can run at a time.")
        return ValidationResult.passed(text)


_WRITE_KEYWORDS = (
    "INSERT", "UPDATE", "DELETE", "REPLACE INTO", "UPSERT", "DROP", "CREATE", "ALTER",
    "TRUNCATE", "VACUUM", "REINDEX", "ANALYZE", "GRANT", "REVOKE", "MERGE",
)


class ReadOnlyRule(SqlRuleDecorator):
    name = "read_only"

    def _apply(self, sql: str) -> ValidationResult:
        bare = strip_literals_and_comments(sql).strip()
        first = re.match(r"[A-Za-z]+", bare)
        if not first or first.group(0).upper() not in ("SELECT", "WITH"):
            return ValidationResult.failed(sql, self.name, "Only SELECT queries are allowed; the data is read only.")
        for word in _WRITE_KEYWORDS:
            if re.search(r"\b" + word.replace(" ", r"\s+") + r"\b", bare, re.IGNORECASE):
                return ValidationResult.failed(sql, self.name, f"{word} is not allowed; the data is read only.")
        return ValidationResult.passed(sql)


_FORBIDDEN = ("PRAGMA", "ATTACH", "DETACH", "load_extension", "sqlite_master", "sqlite_schema",
              "sqlite_temp_master", "readfile", "writefile", "fts3_tokenizer")


class ForbiddenObjectRule(SqlRuleDecorator):
    name = "forbidden_object"

    def _apply(self, sql: str) -> ValidationResult:
        bare = strip_literals_and_comments(sql)
        for word in _FORBIDDEN:
            if re.search(rf"\b{re.escape(word)}\b", bare, re.IGNORECASE):
                return ValidationResult.failed(sql, self.name, f"{word} cannot be used in a query.")
        return ValidationResult.passed(sql)


class RowLimitRule(SqlRuleDecorator):
    name = "row_limit"

    def __init__(self, wrapped: SqlRule, max_rows: int = 1000) -> None:
        super().__init__(wrapped)
        self.max_rows = max_rows

    def _apply(self, sql: str) -> ValidationResult:
        return ValidationResult.passed(f"SELECT * FROM ({sql}) LIMIT {self.max_rows}")
