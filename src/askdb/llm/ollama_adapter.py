from __future__ import annotations

import time

from askdb.llm.provider import LLMError, LLMProvider, LLMResponse, post_json


class OllamaAdapter(LLMProvider):
    """Adapter for a local Ollama server (adaptee: the Ollama HTTP API)."""

    tier = "local"

    def __init__(self, model: str, base_url: str = "http://localhost:11434", timeout_s: float = 180.0) -> None:
        self._model = model
        self.base_url = base_url.rstrip("/")
        self.timeout_s = timeout_s

    def model_name(self) -> str:
        return self._model

    def complete(self, messages: list[dict], max_tokens: int = 1024) -> LLMResponse:
        payload = {
            "model": self._model,
            "messages": messages,
            "stream": False,
            "format": "json",
            "options": {"temperature": 0, "num_predict": max_tokens},
        }
        start = time.perf_counter()
        body = post_json(f"{self.base_url}/api/chat", payload, {}, self.timeout_s)
        if "error" in body:
            raise LLMError(f"Ollama: {body['error']}")
        return LLMResponse(
            text=body.get("message", {}).get("content", ""),
            provider="ollama",
            model=self._model,
            input_tokens=int(body.get("prompt_eval_count", 0)),
            output_tokens=int(body.get("eval_count", 0)),
            latency_ms=(time.perf_counter() - start) * 1000,
            cost_usd=0.0,
        )
