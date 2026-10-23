from __future__ import annotations

import sqlite3
from pathlib import Path

from askdb.data.datasource import DataSource
from askdb.data.errors import DatasetImportError

SQLITE_HEADER = b"SQLite format 3\x00"


class SQLiteDataSource(DataSource):
    """A SQLite file opened read-only, so no statement can change it."""

    def __init__(self, path: str | Path) -> None:
        super().__init__()
        self.path = Path(path)

    @property
    def label(self) -> str:
        return self.path.name

    def connect(self) -> None:
        try:
            with self.path.open("rb") as handle:
                header = handle.read(len(SQLITE_HEADER))
        except OSError as exc:
            raise DatasetImportError(f"Cannot open {self.path.name}: {exc.strerror}") from exc
        if header != SQLITE_HEADER:
            raise DatasetImportError(f"{self.path.name} is not a valid SQLite database.")
        uri = f"{self.path.resolve().as_uri()}?mode=ro"
        try:
            conn = sqlite3.connect(uri, uri=True, check_same_thread=False)
            conn.execute("SELECT count(*) FROM sqlite_master").fetchone()
        except sqlite3.Error as exc:
            raise DatasetImportError(f"{self.path.name} could not be read: {exc}") from exc
        self._conn = conn
