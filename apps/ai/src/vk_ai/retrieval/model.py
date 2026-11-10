from __future__ import annotations

from dataclasses import dataclass

Vector = tuple[float, ...]


@dataclass(frozen=True, slots=True)
class Match:
    chunk_id: str
    title: str
    heading: str
    excerpt: str
    source_url: str
    score: float
