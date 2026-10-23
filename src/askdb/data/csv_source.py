from __future__ import annotations

import re
import sqlite3
from pathlib import Path

import pandas as pd

from askdb.data.datasource import DataSource
from askdb.data.errors import DatasetImportError


def _table_name(path: Path) -> str:
    name = re.sub(r"\W+", "_", path.stem).strip("_").lower() or "table"
    if name[0].isdigit():
        name = f"t_{name}"
    return name


class CsvDataSource(DataSource):
    """Adapter: CSV files become tables of an in-memory SQLite database."""

    def __init__(self, paths: list[str | Path]) -> None:
        super().__init__()
        self.paths = [Path(p) for p in paths]
        self.table_names: dict[str, Path] = {}

    @property
    def label(self) -> str:
        return ", ".join(p.name for p in self.paths)

    def connect(self) -> None:
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        used: set[str] = set()
        for path in self.paths:
            frame = self._read(path)
            name = base = _table_name(path)
            suffix = 2
            while name in used:
                name = f"{base}_{suffix}"
                suffix += 1
            used.add(name)
            frame.to_sql(name, conn, index=False)
            self.table_names[name] = path
        conn.execute("PRAGMA query_only = ON")
        self._conn = conn

    @staticmethod
    def _read(path: Path) -> pd.DataFrame:
        try:
            frame = pd.read_csv(path, on_bad_lines="error")
        except FileNotFoundError as exc:
            raise DatasetImportError(f"{path.name} was not found.") from exc
        except pd.errors.EmptyDataError as exc:
            raise DatasetImportError(f"{path.name} is empty.") from exc
        except pd.errors.ParserError as exc:
            raise DatasetImportError(f"{path.name} has inconsistent rows: {exc}") from exc
        except UnicodeDecodeError as exc:
            raise DatasetImportError(f"{path.name} is not a UTF-8 text file.") from exc
        if any(str(c).startswith("Unnamed:") for c in frame.columns) or frame.columns.empty:
            raise DatasetImportError(f"{path.name} has no header row or has blank column names.")
        frame.columns = [re.sub(r"\W+", "_", str(c)).strip("_") or f"col{i}" for i, c in enumerate(frame.columns)]
        for col in frame.columns:
            if frame[col].dtype == object:
                parsed = pd.to_numeric(frame[col], errors="coerce")
                if parsed.notna().sum() == frame[col].notna().sum() and frame[col].notna().any():
                    frame[col] = parsed
        return frame
