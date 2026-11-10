from __future__ import annotations

import math
from dataclasses import dataclass

from vk_ai.corpus.model import Chunk
from vk_ai.retrieval.model import Match, Vector


@dataclass(frozen=True, slots=True)
class _IndexedChunk:
    chunk: Chunk
    vector: Vector
    norm: float


class VectorIndex:
    def __init__(self, entries: tuple[_IndexedChunk, ...] = ()) -> None:
        self._entries = entries

    @classmethod
    def build(cls, chunks: list[Chunk], vectors: list[Vector]) -> VectorIndex:
        if len(chunks) != len(vectors):
            raise ValueError("chunk and vector counts must match")
        if not chunks:
            raise ValueError("at least one chunk is required")

        dimension = len(vectors[0])
        if dimension == 0:
            raise ValueError("vectors must not be empty")
        entries: list[_IndexedChunk] = []
        for chunk, vector in zip(chunks, vectors, strict=True):
            _validate_vector(vector, dimension)
            entries.append(_IndexedChunk(chunk=chunk, vector=vector, norm=_norm(vector)))
        return cls(tuple(entries))

    @property
    def chunk_count(self) -> int:
        return len(self._entries)

    def search(self, query: Vector, *, top_k: int, minimum_score: float) -> list[Match]:
        if top_k <= 0:
            raise ValueError("top_k must be greater than zero")
        if not self._entries:
            return []
        dimension = len(self._entries[0].vector)
        _validate_vector(query, dimension)
        query_norm = _norm(query)
        if query_norm == 0:
            return []

        scored: list[Match] = []
        for entry in self._entries:
            if entry.norm == 0:
                continue
            score = math.fsum(left * right for left, right in zip(query, entry.vector, strict=True))
            score /= query_norm * entry.norm
            if score < minimum_score:
                continue
            scored.append(
                Match(
                    chunk_id=entry.chunk.chunk_id,
                    title=entry.chunk.title,
                    heading=entry.chunk.heading,
                    excerpt=entry.chunk.text,
                    source_url=entry.chunk.source_url,
                    score=score,
                )
            )

        scored.sort(key=lambda match: (-match.score, match.chunk_id))
        return scored[:top_k]


def _validate_vector(vector: Vector, dimension: int) -> None:
    if len(vector) != dimension:
        raise ValueError("vector dimensions must match")
    if any(not math.isfinite(value) for value in vector):
        raise ValueError("vectors must contain only finite values")


def _norm(vector: Vector) -> float:
    return math.sqrt(math.fsum(value * value for value in vector))
