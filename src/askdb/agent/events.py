"""AgentEvent and AgentEventListener: the notification data and observer interface (Observer pattern)."""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass
class AgentEvent:
    kind: str
    message: str
    step: int = 0
    data: dict[str, Any] = field(default_factory=dict)
    timestamp: float = field(default_factory=time.time)


class AgentEventListener(ABC):
    @abstractmethod
    def on_event(self, event: AgentEvent) -> None:
        ...
