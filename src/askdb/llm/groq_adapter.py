from __future__ import annotations

import time

from askdb.llm.provider import LLMError, LLMProvider, LLMResponse, post_json

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"


class GroqClient:
    """Adaptee: the Groq chat completions API."""

    def __init__(self, api_key: str, model: str, timeout_s: float) -> None:
        self._api_key = api_key
        self.model = model
        self.timeout_s = timeout_s

    def generate(self, payload: dict) -> dict:
        return post_json(GROQ_URL, payload, {"Authorization": f"Bearer {self._api_key}"}, self.timeout_s)


class GroqAdapter(LLMProvider):
    """Converts the neutral message list to the Groq format and back."""

    tier = "strong"

    def __init__(self, client: GroqClient) -> None:
        self._client = client

    def model_name(self) -> str:
        return self._client.model

    def complete(self, messages: list[dict], max_tokens: int = 1024) -> LLMResponse:
        payload = {
            "model": self._client.model,
            "messages": messages,
            "temperature": 0,
            "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
        }
        start = time.perf_counter()
        body = self._client.generate(payload)
        try:
            text = body["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError) as exc:
            raise LLMError("Groq returned no message.") from exc
        usage = body.get("usage", {})
        return LLMResponse(
            text=text, provider="groq", model=self._client.model,
            input_tokens=int(usage.get("prompt_tokens", 0)),
            output_tokens=int(usage.get("completion_tokens", 0)),
            latency_ms=(time.perf_counter() - start) * 1000,
            cost_usd=0.0,
        )
