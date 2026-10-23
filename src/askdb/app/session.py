from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from askdb.data.datasource import DataSource
from askdb.data.models import SchemaInfo


@dataclass
class Session:
    """State of the loaded dataset and the current conversation."""

    data_source: DataSource
    schema: SchemaInfo
    paths: list[str] = field(default_factory=list)
    index: Any = None
    memory: Any = None
    pending_state: Any = None

    def has_pending_clarification(self) -> bool:
        return self.pending_state is not None

    def close(self) -> None:
        self.data_source.close()
