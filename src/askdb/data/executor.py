from __future__ import annotations

from askdb.data.datasource import DataSource
from askdb.data.models import QueryResult


class QueryExecutor:
    """Bounded, timed execution of SQL that has already passed SqlValidator."""

    def __init__(self, timeout_s: float = 10.0, max_rows: int = 1000) -> None:
        self.timeout_s = timeout_s
        self.max_rows = max_rows

    def run(self, sql: str, source: DataSource) -> QueryResult:
        return source.execute(sql, self.timeout_s, self.max_rows)
