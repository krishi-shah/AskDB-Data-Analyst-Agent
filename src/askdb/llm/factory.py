from __future__ import annotations

from askdb.config import Settings
from askdb.llm.gemini_adapter import GeminiAdapter, GeminiClient
from askdb.llm.groq_adapter import GroqAdapter, GroqClient
from askdb.llm.ollama_adapter import OllamaAdapter
from askdb.llm.provider import LLMProvider, ProviderUnavailableError


class LLMProviderFactory:
    """Factory: creates a provider adapter from its configured name."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def available(self) -> list[str]:
        names = ["ollama"]
        if self.settings.gemini_api_key:
            names.append("gemini")
        if self.settings.groq_api_key:
            names.append("groq")
        return names

    def create(self, provider_name: str) -> LLMProvider:
        s = self.settings
        if provider_name == "ollama":
            return OllamaAdapter(s.ollama_model, s.ollama_url, s.llm_timeout_s)
        if provider_name == "gemini":
            if not s.gemini_api_key:
                raise ProviderUnavailableError("GEMINI_API_KEY is not set in .env.")
            return GeminiAdapter(GeminiClient(s.gemini_api_key, s.gemini_model, s.llm_timeout_s))
        if provider_name == "groq":
            if not s.groq_api_key:
                raise ProviderUnavailableError("GROQ_API_KEY is not set in .env.")
            return GroqAdapter(GroqClient(s.groq_api_key, s.groq_model, s.llm_timeout_s))
        raise ValueError(f"Unknown provider: {provider_name}")
