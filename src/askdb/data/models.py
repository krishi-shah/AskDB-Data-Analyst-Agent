"""Plain data objects shared by the data layer, the agent, and the interfaces."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class ColumnInfo:
    name: str
    type: str
    primary_key: bool = False
    references: str | None = None
    description: str = ""


@dataclass
class TableInfo:
    name: str
    columns: list[ColumnInfo]
    row_count: int
    sample_rows: list[tuple] = field(default_factory=list)
    description: str = ""

    def column(self, name: str) -> ColumnInfo | None:
        lowered = name.lower()
        return next((c for c in self.columns if c.name.lower() == lowered), None)


@dataclass
class SchemaInfo:
    tables: list[TableInfo]
    source_label: str = ""

    def table(self, name: str) -> TableInfo | None:
        lowered = name.lower()
        return next((t for t in self.tables if t.name.lower() == lowered), None)

    def summary(self) -> str:
        total_rows = sum(t.row_count for t in self.tables)
        return f"{len(self.tables)} tables, {total_rows} rows"

    def to_text(self, tables: list[str] | None = None) -> str:
        """Compact schema text for prompts."""
        wanted = {t.lower() for t in tables} if tables else None
        lines = []
        for table in self.tables:
            if wanted is not None and table.name.lower() not in wanted:
                continue
            head = f"TABLE {table.name} ({table.row_count} rows)"
            if table.description:
                head += f" -- {table.description}"
            lines.append(head)
            for col in table.columns:
                extra = []
                if col.primary_key:
                    extra.append("PK")
                if col.references:
                    extra.append(f"FK -> {col.references}")
                if col.description:
                    extra.append(col.description)
                suffix = f" -- {'; '.join(extra)}" if extra else ""
                lines.append(f"  {col.name} {col.type}{suffix}")
        return "\n".join(lines)


@dataclass
class QueryResult:
    columns: list[str]
    rows: list[tuple]
    truncated: bool = False
    elapsed_ms: float = 0.0
    sql: str = ""

    @property
    def row_count(self) -> int:
        return len(self.rows)

    def is_empty(self) -> bool:
        return not self.rows

    def column_values(self, index: int) -> list[Any]:
        return [row[index] for row in self.rows]

    def numeric_columns(self) -> list[int]:
        found = []
        for i in range(len(self.columns)):
            values = [v for v in self.column_values(i) if v is not None]
            if values and all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in values):
                found.append(i)
        return found

    def preview_text(self, max_rows: int = 10) -> str:
        """Short text form sent back to the model, never the full result."""
        head = " | ".join(self.columns)
        body = [" | ".join("NULL" if v is None else str(v) for v in row) for row in self.rows[:max_rows]]
        more = ""
        if self.row_count > max_rows:
            more = f"\n... {self.row_count - max_rows} more rows"
        return f"{self.row_count} rows\n{head}\n" + "\n".join(body) + more
