from __future__ import annotations

import logging
import time
from collections.abc import Sequence
from typing import Protocol

from vk_ai.corpus.chunker import chunk_documents
from vk_ai.corpus.model import Document
from vk_ai.retrieval.embedder import EmbeddingDocument, EmbeddingProvider
from vk_ai.retrieval.index import VectorIndex
from vk_ai.retrieval.model import Match

logger = logging.getLogger(__name__)


class CorpusLoader(Protocol):
    async def load_documents(self) -> list[Document]: ...


class RuntimeNotReadyError(RuntimeError):
    """Raised while no complete immutable index is available."""


class RetrievalService:
    def __init__(
        self,
        loader: CorpusLoader,
        embedder: EmbeddingProvider,
        *,
        chunk_max_chars: int,
        chunk_overlap_chars: int,
        minimum_score: float,
    ) -> None:
        self._loader = loader
        self._embedder = embedder
        self._chunk_max_chars = chunk_max_chars
        self._chunk_overlap_chars = chunk_overlap_chars
        self._minimum_score = minimum_score
        self._index: VectorIndex | None = None
        self._document_count = 0

    @property
    def ready(self) -> bool:
        return self._index is not None

    @property
    def document_count(self) -> int:
        return self._document_count

    @property
    def chunk_count(self) -> int:
        return self._index.chunk_count if self._index is not None else 0

    async def rebuild(self) -> None:
        started = time.perf_counter()
        documents = await self._loader.load_documents()
        chunks = chunk_documents(
            documents,
            max_chars=self._chunk_max_chars,
            overlap_chars=self._chunk_overlap_chars,
        )
        if chunks:
            vectors = list(
                await self._embedder.embed_documents(
                    [EmbeddingDocument(text=chunk.text, title=chunk.title) for chunk in chunks]
                )
            )
            replacement = VectorIndex.build(chunks, vectors)
        else:
            replacement = VectorIndex()
        self._index = replacement
        self._document_count = len(documents)
        logger.info(
            "retrieval index ready",
            extra={
                "document_count": len(documents),
                "chunk_count": replacement.chunk_count,
                "build_duration_ms": round((time.perf_counter() - started) * 1_000, 3),
            },
        )

    async def retrieve(self, query: str, top_k: int) -> Sequence[Match]:
        index = self._index
        if index is None:
            raise RuntimeNotReadyError("retrieval index is not ready")
        if index.chunk_count == 0:
            return []
        embedding_started = time.perf_counter()
        query_vector = await self._embedder.embed_query(query)
        embedding_ms = (time.perf_counter() - embedding_started) * 1_000
        scan_started = time.perf_counter()
        matches = index.search(
            query_vector,
            top_k=top_k,
            minimum_score=self._minimum_score,
        )
        logger.info(
            "retrieval completed",
            extra={
                "query_embedding_duration_ms": round(embedding_ms, 3),
                "scan_duration_ms": round((time.perf_counter() - scan_started) * 1_000, 3),
                "chunk_count": index.chunk_count,
                "result_count": len(matches),
            },
        )
        return matches
