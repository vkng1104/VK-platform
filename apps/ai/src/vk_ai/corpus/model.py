from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Document:
    source_id: str
    title: str
    source_url: str
    content: str
    content_hash: str


@dataclass(frozen=True, slots=True)
class Chunk:
    chunk_id: str
    source_id: str
    title: str
    heading: str
    source_url: str
    content_hash: str
    text: str
