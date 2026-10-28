from __future__ import annotations

import os
from abc import ABC, abstractmethod

from askdb.llm.provider import LLMError, post_json


class EmbeddingError(Exception):
    pass


class EmbeddingProvider(ABC):
    @abstractmethod
    def embed(self, texts: list[str]) -> list[list[float]]:
        ...


class LocalEmbeddingProvider(EmbeddingProvider):
    """all-MiniLM-L6-v2 through sentence-transformers, loaded on first use."""

    def __init__(self, model: str = "sentence-transformers/all-MiniLM-L6-v2") -> None:
        self.model_name = model
        self._model = None

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self._model is None:
            os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
            os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")
            os.environ.setdefault("HF_HUB_VERBOSITY", "error")
            try:
                from sentence_transformers import SentenceTransformer
            except ImportError as exc:
                raise EmbeddingError("sentence-transformers is not installed.") from exc
            try:
                from transformers.utils import logging as hf_logging

                hf_logging.disable_progress_bar()
                hf_logging.set_verbosity_error()
            except ImportError:
                pass
            try:
                self._model = SentenceTransformer(self.model_name)
            except Exception as exc:
                raise EmbeddingError(f"Could not load {self.model_name}: {exc}") from exc
        vectors = self._model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
        return [list(map(float, v)) for v in vectors]


class OllamaEmbeddingProvider(EmbeddingProvider):
    """nomic-embed-text through the local Ollama /api/embed endpoint."""

    def __init__(self, base_url: str, model: str = "nomic-embed-text", timeout_s: float = 60.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.model_name = model
        self.timeout_s = timeout_s

    def embed(self, texts: list[str]) -> list[list[float]]:
        try:
            body = post_json(f"{self.base_url}/api/embed", {"model": self.model_name, "input": texts}, {},
                             self.timeout_s)
        except LLMError as exc:
            raise EmbeddingError(str(exc)) from exc
        vectors = body.get("embeddings")
        if not vectors or len(vectors) != len(texts):
            raise EmbeddingError(f"Ollama returned no embeddings: {body.get('error', 'unknown error')}")
        return vectors


def default_embedding_provider(ollama_url: str) -> EmbeddingProvider:
    try:
        import sentence_transformers  # noqa: F401
    except ImportError:
        return OllamaEmbeddingProvider(ollama_url)
    return LocalEmbeddingProvider()
