"""Settings loaded from the environment and an optional local .env file.

Real keys live only in .env, which git ignores. Values that still hold the
placeholder from .env.example are treated as missing.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

PLACEHOLDER = "YOUR_LOCAL_API_KEY_HERE"


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _clean_key(value: str | None) -> str | None:
    if not value or value == PLACEHOLDER:
        return None
    return value


@dataclass
class Settings:
    ollama_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5-coder:7b"
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-2.5-flash"
    groq_api_key: str | None = None
    groq_model: str = "openai/gpt-oss-120b"
    policy: str = "local_only"
    home: Path = field(default_factory=lambda: Path.home() / ".askdb")
    row_limit: int = 1000
    query_timeout_s: float = 10.0
    max_file_mb: int = 200
    max_steps: int = 8
    max_repairs: int = 3
    max_clarifications: int = 2
    memory_turns: int = 5
    share_samples: bool = True
    llm_timeout_s: float = 180.0

    @property
    def app_db(self) -> Path:
        return self.home / "askdb.sqlite"

    @property
    def state_file(self) -> Path:
        return self.home / "state.json"

    def ensure_home(self) -> None:
        self.home.mkdir(parents=True, exist_ok=True)


def load_settings(env_file: str | Path | None = None) -> Settings:
    """Read settings from a .env file (current directory by default) and the environment."""
    file_values = _read_env_file(Path(env_file) if env_file else Path.cwd() / ".env")

    def get(name: str, default: str | None = None) -> str | None:
        return os.environ.get(name) or file_values.get(name) or default

    settings = Settings()
    settings.ollama_url = get("OLLAMA_URL", settings.ollama_url).rstrip("/")
    settings.ollama_model = get("OLLAMA_MODEL", settings.ollama_model)
    settings.gemini_api_key = _clean_key(get("GEMINI_API_KEY"))
    settings.gemini_model = get("GEMINI_MODEL", settings.gemini_model)
    settings.groq_api_key = _clean_key(get("GROQ_API_KEY"))
    settings.groq_model = get("GROQ_MODEL", settings.groq_model)
    settings.policy = get("ASKDB_POLICY", settings.policy)
    home = get("ASKDB_HOME")
    if home:
        settings.home = Path(home).expanduser()
    return settings
