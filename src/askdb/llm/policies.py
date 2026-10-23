"""Routing policies: the concrete states of ModelRouter (State pattern).

providers maps a tier name ("fast", "strong", "local") to an LLMProvider.
"local" is always present; the cloud tiers exist only when a key is set.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from askdb.llm.provider import LLMProvider


class RoutingPolicy(ABC):
    name = "policy"
    allows_cloud = True

    @abstractmethod
    def select(self, task: str, attempt: int, providers: dict[str, LLMProvider]) -> LLMProvider:
        ...

    def fallback_order(self, providers: dict[str, LLMProvider]) -> list[LLMProvider]:
        """Providers to try, in order, after the selected one fails."""
        tiers = ["strong", "fast", "local"] if self.allows_cloud else ["local"]
        return [providers[t] for t in tiers if t in providers]


class CheapFirstPolicy(RoutingPolicy):
    """Fast tier for routine work; strong tier from the second repair attempt."""

    name = "cheap_first"

    def select(self, task: str, attempt: int, providers: dict[str, LLMProvider]) -> LLMProvider:
        if task == "repair" and attempt >= 2 and "strong" in providers:
            return providers["strong"]
        return providers.get("fast") or providers.get("strong") or providers["local"]

    def fallback_order(self, providers: dict[str, LLMProvider]) -> list[LLMProvider]:
        return [providers[t] for t in ("fast", "strong", "local") if t in providers]


class StrongOnlyPolicy(RoutingPolicy):
    name = "strong_only"

    def select(self, task: str, attempt: int, providers: dict[str, LLMProvider]) -> LLMProvider:
        return providers.get("strong") or providers["local"]


class LocalOnlyPolicy(RoutingPolicy):
    """No data leaves the machine."""

    name = "local_only"
    allows_cloud = False

    def select(self, task: str, attempt: int, providers: dict[str, LLMProvider]) -> LLMProvider:
        return providers["local"]


POLICIES = {cls.name: cls for cls in (CheapFirstPolicy, StrongOnlyPolicy, LocalOnlyPolicy)}


def policy_by_name(name: str) -> RoutingPolicy:
    try:
        return POLICIES[name]()
    except KeyError as exc:
        raise ValueError(f"Unknown policy '{name}'. Choose one of: {', '.join(POLICIES)}.") from exc
