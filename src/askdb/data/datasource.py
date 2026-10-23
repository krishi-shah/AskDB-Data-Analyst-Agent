"""DataSource is the target interface of the Adapter pattern for data access."""

from __future__ import annotations

import sqlite3
import time
from abc import ABC, abstractmethod

from askdb.data.errors import QueryError, QueryTimeoutError
from askdb.data.models import QueryResult


class DataSource(ABC):
    """One relational interface, whether the data came from SQLite or CSV."""

    def __init__(self) -> None:
        self._conn: sqlite3.Connection | None = None

    @property
    @abstractmethod
    def label(self) -> str:
        """Short name shown to the user."""

    @abstractmethod
    def connect(self) -> None:
        """Open a read-only connection."""

    @property
    def connection(self) -> sqlite3.Connection:
        if self._conn is None:
            raise QueryError("The dataset is not connected.")
        return self._conn

    def list_tables(self) -> list[str]:
        rows = self.connection.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') "
            "AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).fetchall()
        return [r[0] for r in rows]

    def execute(self, sql: str, timeout_s: float, max_rows: int | None = None) -> QueryResult:
        """Run one statement. A progress handler cancels it after timeout_s seconds."""
        conn = self.connection
        deadline = time.monotonic() + timeout_s
        timed_out = False

        def check() -> int:
            nonlocal timed_out
            if time.monotonic() > deadline:
                timed_out = True
                return 1
            return 0

        conn.set_progress_handler(check, 10_000)
        start = time.perf_counter()
        try:
            cursor = conn.execute(sql)
            columns = [d[0] for d in cursor.description or []]
            if max_rows is None:
                rows = cursor.fetchall()
                truncated = False
            else:
                rows = cursor.fetchmany(max_rows + 1)
                truncated = len(rows) > max_rows
                rows = rows[:max_rows]
        except sqlite3.Error as exc:
            if timed_out:
                raise QueryTimeoutError(f"The query was cancelled after {timeout_s:g} seconds.") from exc
            raise QueryError(str(exc)) from exc
        finally:
            conn.set_progress_handler(None, 0)
        elapsed = (time.perf_counter() - start) * 1000
        return QueryResult(columns=columns, rows=[tuple(r) for r in rows], truncated=truncated,
                           elapsed_ms=elapsed, sql=sql)

    def close(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None
