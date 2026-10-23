from __future__ import annotations

from pathlib import Path

from askdb.data.csv_source import CsvDataSource
from askdb.data.datasource import DataSource
from askdb.data.errors import FileTooLargeError, UnsupportedFileError
from askdb.data.sqlite_source import SQLiteDataSource

SQLITE_EXTENSIONS = {".db", ".sqlite", ".sqlite3"}
CSV_EXTENSIONS = {".csv"}


class DataSourceFactory:
    """Factory: picks the DataSource class from the file extensions."""

    def __init__(self, max_file_mb: int = 200) -> None:
        self.max_file_mb = max_file_mb

    def create(self, paths: list[str | Path]) -> DataSource:
        files = [Path(p) for p in paths]
        if not files:
            raise UnsupportedFileError("Choose a SQLite file or one or more CSV files.")
        for path in files:
            if path.is_file():
                size_mb = path.stat().st_size / (1024 * 1024)
                if size_mb > self.max_file_mb:
                    raise FileTooLargeError(
                        f"{path.name} is {size_mb:.1f} MB; the limit is {self.max_file_mb} MB."
                    )
        extensions = {p.suffix.lower() for p in files}
        if extensions <= SQLITE_EXTENSIONS:
            if len(files) != 1:
                raise UnsupportedFileError("Import one SQLite database at a time.")
            return SQLiteDataSource(files[0])
        if extensions <= CSV_EXTENSIONS:
            return CsvDataSource(files)
        supported = ", ".join(sorted(SQLITE_EXTENSIONS | CSV_EXTENSIONS))
        raise UnsupportedFileError(f"Unsupported file type. Supported types: {supported}.")
