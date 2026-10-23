from __future__ import annotations

import time

from askdb.llm.provider import LLMError, LLMProvider, LLMResponse, post_json

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class GeminiClient:
    """Adaptee: the Gemini REST API."""

    def __init__(self, api_key: str, model: str, timeout_s: float) -> None:
        self._api_key = api_key
        self.model = model
        self.timeout_s = timeout_s

    def generate(self, payload: dict) -> dict:
        return post_json(GEMINI_URL.format(model=self.model), payload,
                         {"x-goog-api-key": self._api_key}, self.timeout_s)


class GeminiAdapter(LLMProvider):
    """Converts the neutral message list to the Gemini format and back."""

    tier = "fast"

    def __init__(self, client: GeminiClient) -> None:
        self._client = client

    def model_name(self) -> str:
        return self._client.model

    def complete(self, messages: list[dict], max_tokens: int = 1024) -> LLMResponse:
        system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
        contents = [
            {"role": "model" if m["role"] == "assistant" else "user", "parts": [{"text": m["content"]}]}
            for m in messages if m["role"] != "system"
        ]
        payload = {
            "contents": contents,
            "generationConfig": {"temperature": 0, "maxOutputTokens": max_tokens,
                                 "responseMimeType": "application/json"},
        }
        if system:
            payload["systemInstruction"] = {"parts": [{"text": system}]}
        start = time.perf_counter()
        body = self._client.generate(payload)
        try:
            text = "".join(p.get("text", "") for p in body["candidates"][0]["content"]["parts"])
        except (KeyError, IndexError) as exc:
            raise LLMError("Gemini returned no candidate text.") from exc
        usage = body.get("usageMetadata", {})
        return LLMResponse(
            text=text, provider="gemini", model=self._client.model,
            input_tokens=int(usage.get("promptTokenCount", 0)),
            output_tokens=int(usage.get("candidatesTokenCount", 0)),
            latency_ms=(time.perf_counter() - start) * 1000,
            cost_usd=0.0,
        )
