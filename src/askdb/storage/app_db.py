"""The application database (separate from the analysed dataset, which is never written)."""

from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    dataset TEXT NOT NULL,
    question TEXT NOT NULL,
    sql TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    row_count INTEGER NOT NULL DEFAULT 0,
    summary TEXT NOT NULL DEFAULT '',
    manual INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS saved_questions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    question TEXT NOT NULL,
    sql TEXT NOT NULL,
    dataset TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE TABLE IF NOT EXISTS descriptions (
    dataset TEXT NOT NULL,
    entry_id TEXT NOT NULL,
    text TEXT NOT NULL,
    edited INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (dataset, entry_id)
);
CREATE TABLE IF NOT EXISTS usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    task TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    latency_ms REAL NOT NULL,
    cost_usd REAL NOT NULL,
    ok INTEGER NOT NULL,
    fallback INTEGER NOT NULL
);
"""


def connect(path: str | Path) -> sqlite3.Connection:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    return conn
