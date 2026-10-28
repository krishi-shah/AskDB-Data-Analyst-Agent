from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass
class HistoryEntry:
    question: str
    sql: str
    status: str
    dataset: str
    row_count: int = 0
    summary: str = ""
    manual: bool = False
    id: int | None = None
    created_at: str = ""


class HistoryRepository:
    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn

    def add(self, entry: HistoryEntry) -> int:
        cursor = self.conn.execute(
            "INSERT INTO history (dataset, question, sql, status, row_count, summary, manual) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (entry.dataset, entry.question, entry.sql, entry.status, entry.row_count, entry.summary,
             int(entry.manual)),
        )
        self.conn.commit()
        entry.id = cursor.lastrowid
        return entry.id

    def recent(self, limit: int = 50) -> list[HistoryEntry]:
        rows = self.conn.execute("SELECT * FROM history ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [self._entry(r) for r in rows]

    def search(self, text: str, limit: int = 50) -> list[HistoryEntry]:
        like = f"%{text}%"
        rows = self.conn.execute(
            "SELECT * FROM history WHERE question LIKE ? OR sql LIKE ? OR summary LIKE ? ORDER BY id DESC LIMIT ?",
            (like, like, like, limit),
        ).fetchall()
        return [self._entry(r) for r in rows]

    def get(self, entry_id: int) -> HistoryEntry | None:
        row = self.conn.execute("SELECT * FROM history WHERE id = ?", (entry_id,)).fetchone()
        return self._entry(row) if row else None

    @staticmethod
    def _entry(row: sqlite3.Row) -> HistoryEntry:
        return HistoryEntry(question=row["question"], sql=row["sql"], status=row["status"], dataset=row["dataset"],
                            row_count=row["row_count"], summary=row["summary"], manual=bool(row["manual"]),
                            id=row["id"], created_at=row["created_at"])
