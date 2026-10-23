"""LLMProvider is the target interface of the Adapter pattern for model access."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class LLMResponse:
    text: str
    provider: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: float = 0.0
    cost_usd: float = 0.0


class LLMError(Exception):
    """A provider could not answer."""


class RateLimitError(LLMError):
    pass


class ProviderTimeoutError(LLMError):
    pass


class ProviderUnavailableError(LLMError):
    pass


class LLMProvider(ABC):
    """Every model, local or cloud, is called through this interface."""

    tier = "local"

    @abstractmethod
    def complete(self, messages: list[dict], max_tokens: int = 1024) -> LLMResponse:
        """messages is a list of {"role": "system"|"user"|"assistant", "content": str}."""

    @abstractmethod
    def model_name(self) -> str:
        ...

    @property
    def is_cloud(self) -> bool:
        return self.tier != "local"


def post_json(url: str, payload: dict, headers: dict[str, str], timeout_s: float) -> dict:
    """Small HTTP helper shared by the adapters. Maps transport errors to LLMError types."""
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")[:300]
        if exc.code == 429:
            raise RateLimitError(f"Rate limited (HTTP 429): {body}") from exc
        if exc.code in (401, 403):
            raise ProviderUnavailableError(f"The API key was rejected (HTTP {exc.code}).") from exc
        raise LLMError(f"HTTP {exc.code}: {body}") from exc
    except TimeoutError as exc:
        raise ProviderTimeoutError(f"No reply within {timeout_s:g} seconds.") from exc
    except urllib.error.URLError as exc:
        if isinstance(exc.reason, TimeoutError):
            raise ProviderTimeoutError(f"No reply within {timeout_s:g} seconds.") from exc
        raise ProviderUnavailableError(f"Cannot reach {url}: {exc.reason}") from exc
