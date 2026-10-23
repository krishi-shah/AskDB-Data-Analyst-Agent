from __future__ import annotations

import time
from typing import Callable

from askdb.llm.policies import LocalOnlyPolicy, RoutingPolicy
from askdb.llm.provider import LLMError, LLMProvider, LLMResponse, RateLimitError

CallListener = Callable[[str, LLMResponse | None, str], None]


class ModelRouter:
    """Context of the State pattern: the current RoutingPolicy chooses the provider for each call."""

    def __init__(self, providers: dict[str, LLMProvider], policy: RoutingPolicy | None = None,
                 retry_delays_s: tuple[float, ...] = (1.0, 2.0)) -> None:
        if "local" not in providers:
            raise ValueError("A local provider is required.")
        self.providers = providers
        self._policy = policy or LocalOnlyPolicy()
        self.retry_delays_s = retry_delays_s
        self.listener: CallListener | None = None

    @property
    def policy(self) -> RoutingPolicy:
        return self._policy

    def set_policy(self, policy: RoutingPolicy) -> None:
        self._policy = policy

    def add_provider(self, tier: str, provider: LLMProvider) -> None:
        self.providers[tier] = provider

    def complete(self, messages: list[dict], task: str, attempt: int = 1, max_tokens: int = 1024) -> LLMResponse:
        provider = self._policy.select(task, attempt, self.providers)
        try:
            response = self._call_with_backoff(provider, messages, max_tokens)
        except LLMError as exc:
            self._notify(task, None, f"{provider.model_name()} failed: {exc}")
            return self.fallback(provider, messages, task, max_tokens, exc)
        self._notify(task, response, "")
        return response

    def fallback(self, failed: LLMProvider, messages: list[dict], task: str = "",
                 max_tokens: int = 1024, error: Exception | None = None) -> LLMResponse:
        last_error = error
        for provider in self._policy.fallback_order(self.providers):
            if provider is failed:
                continue
            try:
                response = provider.complete(messages, max_tokens)
            except LLMError as exc:
                last_error = exc
                self._notify(task, None, f"{provider.model_name()} failed: {exc}")
                continue
            self._notify(task, response, f"fallback from {failed.model_name()}")
            return response
        raise LLMError(f"No model could answer. Last error: {last_error}")

    def _call_with_backoff(self, provider: LLMProvider, messages: list[dict], max_tokens: int) -> LLMResponse:
        for delay in (*self.retry_delays_s, None):
            try:
                return provider.complete(messages, max_tokens)
            except RateLimitError:
                if delay is None:
                    raise
                time.sleep(delay)
        raise AssertionError("unreachable")

    def _notify(self, task: str, response: LLMResponse | None, note: str) -> None:
        if self.listener is not None:
            self.listener(task, response, note)
