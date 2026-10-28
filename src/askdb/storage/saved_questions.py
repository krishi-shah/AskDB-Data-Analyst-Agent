from __future__ import annotations

import sqlite3
from dataclasses import dataclass


@dataclass
class SavedQuestion:
    name: str
    question: str
    sql: str
    dataset: str
    id: int | None = None
    created_at: str = ""


class SavedQuestionRepository:
    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn

    def save(self, item: SavedQuestion) -> None:
        """Saving under an existing name replaces it."""
        self.conn.execute(
            "INSERT INTO saved_questions (name, question, sql, dataset) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(name) DO UPDATE SET question = excluded.question, sql = excluded.sql, "
            "dataset = excluded.dataset",
            (item.name, item.question, item.sql, item.dataset),
        )
        self.conn.commit()

    def list(self) -> list[SavedQuestion]:
        rows = self.conn.execute("SELECT * FROM saved_questions ORDER BY name").fetchall()
        return [self._item(r) for r in rows]

    def get(self, name: str) -> SavedQuestion | None:
        row = self.conn.execute("SELECT * FROM saved_questions WHERE name = ?", (name,)).fetchone()
        return self._item(row) if row else None

    def delete(self, name: str) -> bool:
        cursor = self.conn.execute("DELETE FROM saved_questions WHERE name = ?", (name,))
        self.conn.commit()
        return cursor.rowcount > 0

    @staticmethod
    def _item(row: sqlite3.Row) -> SavedQuestion:
        return SavedQuestion(name=row["name"], question=row["question"], sql=row["sql"], dataset=row["dataset"],
                             id=row["id"], created_at=row["created_at"])
