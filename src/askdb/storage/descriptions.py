from __future__ import annotations

import sqlite3

from askdb.data.models import SchemaInfo


class DescriptionStore:
    """Caches generated descriptions per dataset and keeps the analyst's edits."""

    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn

    def has(self, dataset: str) -> bool:
        return self.conn.execute("SELECT 1 FROM descriptions WHERE dataset = ? LIMIT 1", (dataset,)).fetchone() is not None

    def save_all(self, dataset: str, schema: SchemaInfo) -> None:
        for table in schema.tables:
            self._put(dataset, table.name, table.description, edited=False)
            for col in table.columns:
                self._put(dataset, f"{table.name}.{col.name}", col.description, edited=False)
        self.conn.commit()

    def save_edit(self, dataset: str, entry_id: str, text: str) -> None:
        self.conn.execute(
            "INSERT INTO descriptions (dataset, entry_id, text, edited) VALUES (?, ?, ?, 1) "
            "ON CONFLICT(dataset, entry_id) DO UPDATE SET text = excluded.text, edited = 1",
            (dataset, entry_id, text),
        )
        self.conn.commit()

    def apply(self, dataset: str, schema: SchemaInfo) -> None:
        rows = self.conn.execute("SELECT entry_id, text FROM descriptions WHERE dataset = ?", (dataset,)).fetchall()
        texts = {r["entry_id"]: r["text"] for r in rows}
        for table in schema.tables:
            table.description = texts.get(table.name, table.description)
            for col in table.columns:
                col.description = texts.get(f"{table.name}.{col.name}", col.description)

    def _put(self, dataset: str, entry_id: str, text: str, edited: bool) -> None:
        self.conn.execute(
            "INSERT INTO descriptions (dataset, entry_id, text, edited) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(dataset, entry_id) DO UPDATE SET text = excluded.text WHERE edited = 0",
            (dataset, entry_id, text, int(edited)),
        )
