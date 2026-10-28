"""Semantic search over table and column entries (F02).

If the embedding model cannot be used, the index falls back to keyword
overlap, so questions still work offline without the model.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

from askdb.data.models import SchemaInfo
from askdb.retrieval.embeddings import EmbeddingError, EmbeddingProvider


@dataclass
class SchemaEntry:
    entry_id: str
    table: str
    text: str
    vector: list[float] | None = None
    score: float = 0.0


def _tokens(text: str) -> set[str]:
    words = re.findall(r"[a-z0-9]+", text.lower())
    out = set(words)
    out.update(w[:-1] for w in words if w.endswith("s") and len(w) > 3)
    return out


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


class SchemaIndex:
    def __init__(self, embedder: EmbeddingProvider | None) -> None:
        self.embedder = embedder
        self.entries: dict[str, SchemaEntry] = {}
        self.mode = "keyword"
        self.warning = ""

    def build(self, schema: SchemaInfo) -> None:
        self.entries = {}
        for table in schema.tables:
            cols = ", ".join(c.name for c in table.columns)
            text = f"table {table.name}: {table.description or ''} columns: {cols}".replace(":  ", ": ")
            self.entries[table.name] = SchemaEntry(table.name, table.name, text)
            for col in table.columns:
                desc = f" - {col.description}" if col.description else ""
                key = " PK" if col.primary_key else (f" FK->{col.references}" if col.references else "")
                eid = f"{table.name}.{col.name}"
                self.entries[eid] = SchemaEntry(eid, table.name, f"{eid} {col.type}{key}{desc}")
        self._embed_all()

    def _embed_all(self) -> None:
        if self.embedder is None:
            self.mode = "keyword"
            return
        try:
            items = list(self.entries.values())
            vectors = self.embedder.embed([e.text for e in items])
            for entry, vector in zip(items, vectors):
                entry.vector = vector
            self.mode = "semantic"
            self.warning = ""
        except EmbeddingError as exc:
            self.mode = "keyword"
            self.warning = f"Semantic search unavailable, using keyword search: {exc}"

    def search(self, query: str, k: int = 6) -> list[SchemaEntry]:
        if not self.entries:
            return []
        scores: dict[str, float] = {}
        if self.mode == "semantic":
            try:
                qv = self.embedder.embed([query])[0]
                scores = {eid: _cosine(qv, e.vector) for eid, e in self.entries.items() if e.vector}
            except EmbeddingError:
                scores = {}
        q = _tokens(query)
        for eid, entry in self.entries.items():
            overlap = len(q & _tokens(entry.text)) / (len(q) or 1)
            scores[eid] = scores.get(eid, 0.0) + 0.5 * overlap
        ranked = sorted(self.entries.values(), key=lambda e: scores.get(e.entry_id, 0.0), reverse=True)
        hits = []
        for entry in ranked[:k]:
            entry.score = round(scores.get(entry.entry_id, 0.0), 3)
            hits.append(entry)
        return hits

    def update_entry(self, entry_id: str, text: str) -> None:
        entry = self.entries.get(entry_id)
        if entry is None:
            return
        entry.text = text
        if self.mode == "semantic":
            try:
                entry.vector = self.embedder.embed([text])[0]
            except EmbeddingError:
                entry.vector = None
