from __future__ import annotations

from askdb.data.datasource import DataSource
from askdb.data.models import ColumnInfo, SchemaInfo, TableInfo


def _quote(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


class SchemaReader:
    """Reads tables, columns, keys, row counts, and sample rows."""

    def __init__(self, sample_size: int = 5) -> None:
        self.sample_size = sample_size

    def read(self, source: DataSource) -> SchemaInfo:
        conn = source.connection
        tables = []
        for name in source.list_tables():
            quoted = _quote(name)
            fks = {row[3]: f"{row[2]}.{row[4]}" for row in conn.execute(f"PRAGMA foreign_key_list({quoted})")}
            columns = [
                ColumnInfo(name=row[1], type=row[2] or "ANY", primary_key=bool(row[5]), references=fks.get(row[1]))
                for row in conn.execute(f"PRAGMA table_info({quoted})")
            ]
            count = conn.execute(f"SELECT COUNT(*) FROM {quoted}").fetchone()[0]
            sample = [tuple(r) for r in conn.execute(f"SELECT * FROM {quoted} LIMIT {self.sample_size}")]
            tables.append(TableInfo(name=name, columns=columns, row_count=count, sample_rows=sample))
        return SchemaInfo(tables=tables, source_label=source.label)
