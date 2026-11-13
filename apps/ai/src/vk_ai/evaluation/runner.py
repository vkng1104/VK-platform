from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import dataclass

from vk_ai.corpus.chunker import chunk_documents
from vk_ai.corpus.model import Chunk, Document
from vk_ai.evaluation.dataset import GoldenQuerySet
from vk_ai.evaluation.metrics import (
    QueryOutcome,
    RetrievalMetrics,
    ThresholdSuggestion,
    percentile,
    score_query,
    suggest_threshold,
    summarize,
)
from vk_ai.retrieval.embedder import EmbeddingDocument, EmbeddingProvider
from vk_ai.retrieval.index import VectorIndex
from vk_ai.retrieval.model import Vector

TITLE_MODES = ("title", "title-heading")


@dataclass(frozen=True, slots=True)
class Variant:
    chunk_max_chars: int
    chunk_overlap_chars: int
    input_format: str
    title_mode: str

    def __post_init__(self) -> None:
        if self.chunk_max_chars <= 0 or self.chunk_overlap_chars < 0:
            raise ValueError("chunk sizes must be positive and overlap must not be negative")
        if self.chunk_overlap_chars >= self.chunk_max_chars:
            raise ValueError("chunk overlap must be smaller than the chunk size")
        if self.title_mode not in TITLE_MODES:
            raise ValueError(f"title_mode must be one of {', '.join(TITLE_MODES)}")

    @property
    def label(self) -> str:
        return (
            f"{self.chunk_max_chars}/{self.chunk_overlap_chars} "
            f"{self.input_format} {self.title_mode}"
        )


@dataclass(frozen=True, slots=True)
class VariantResult:
    variant: Variant
    chunk_count: int
    outcomes: tuple[QueryOutcome, ...]
    metrics: RetrievalMetrics
    threshold: ThresholdSuggestion | None


@dataclass(frozen=True, slots=True)
class LatencyReport:
    chunk_count: int
    index_build_ms: float
    query_embedding_ms: tuple[float, ...]
    scan_ms: tuple[float, ...]

    @property
    def query_embedding_median_ms(self) -> float:
        return percentile(self.query_embedding_ms, 0.5)

    @property
    def query_embedding_p95_ms(self) -> float:
        return percentile(self.query_embedding_ms, 0.95)

    @property
    def scan_median_ms(self) -> float:
        return percentile(self.scan_ms, 0.5)

    @property
    def scan_p95_ms(self) -> float:
        return percentile(self.scan_ms, 0.95)


class CachingEmbedder:
    """Reuse vectors for identical inputs, so variants with equal chunks embed them once."""

    def __init__(self, inner: EmbeddingProvider) -> None:
        self._inner = inner
        self._documents: dict[tuple[str | None, str], Vector] = {}
        self._queries: dict[str, Vector] = {}

    async def embed_documents(self, documents: Sequence[EmbeddingDocument]) -> Sequence[Vector]:
        missing = list(
            {
                (document.title, document.text): document
                for document in documents
                if (document.title, document.text) not in self._documents
            }.values()
        )
        if missing:
            vectors = await self._inner.embed_documents(missing)
            if len(vectors) != len(missing):
                raise ValueError("embedding provider returned the wrong vector count")
            for document, vector in zip(missing, vectors, strict=True):
                self._documents[(document.title, document.text)] = vector
        return [self._documents[(document.title, document.text)] for document in documents]

    async def embed_query(self, text: str) -> Vector:
        if text not in self._queries:
            self._queries[text] = await self._inner.embed_query(text)
        return self._queries[text]


def embedding_documents(chunks: Sequence[Chunk], title_mode: str) -> list[EmbeddingDocument]:
    if title_mode not in TITLE_MODES:
        raise ValueError(f"title_mode must be one of {', '.join(TITLE_MODES)}")
    documents: list[EmbeddingDocument] = []
    for chunk in chunks:
        title = chunk.title
        if title_mode == "title-heading" and chunk.heading != chunk.title:
            title = f"{chunk.title} — {chunk.heading}"
        documents.append(EmbeddingDocument(text=chunk.text, title=title))
    return documents


async def evaluate_variant(
    documents: Sequence[Document],
    golden: GoldenQuerySet,
    embedder: EmbeddingProvider,
    variant: Variant,
) -> VariantResult:
    """Rank every chunk for every golden query, without a score threshold."""

    chunks = chunk_documents(
        documents,
        max_chars=variant.chunk_max_chars,
        overlap_chars=variant.chunk_overlap_chars,
    )
    vectors = await embedder.embed_documents(embedding_documents(chunks, variant.title_mode))
    index = VectorIndex.build(chunks, list(vectors))
    outcomes: list[QueryOutcome] = []
    for query in golden.queries:
        query_vector = await embedder.embed_query(query.question)
        matches = index.search(query_vector, top_k=index.chunk_count, minimum_score=-1.0)
        outcomes.append(score_query(query, matches))
    in_scope_scores = [
        outcome.expected_score
        for outcome in outcomes
        if outcome.in_scope and outcome.expected_score is not None
    ]
    out_of_scope_scores = [
        outcome.top_score
        for outcome in outcomes
        if not outcome.in_scope and outcome.top_score is not None
    ]
    return VariantResult(
        variant=variant,
        chunk_count=index.chunk_count,
        outcomes=tuple(outcomes),
        metrics=summarize(outcomes),
        threshold=suggest_threshold(in_scope_scores, out_of_scope_scores),
    )


async def measure_latency(
    documents: Sequence[Document],
    golden: GoldenQuerySet,
    embedder: EmbeddingProvider,
    variant: Variant,
    *,
    top_k: int = 5,
) -> LatencyReport:
    """Time an uncached index build and each query, after one warm-up call loads the model."""

    await embedder.embed_query("warm-up")
    started = time.perf_counter()
    chunks = chunk_documents(
        documents,
        max_chars=variant.chunk_max_chars,
        overlap_chars=variant.chunk_overlap_chars,
    )
    vectors = await embedder.embed_documents(embedding_documents(chunks, variant.title_mode))
    index = VectorIndex.build(chunks, list(vectors))
    index_build_ms = (time.perf_counter() - started) * 1_000

    embedding_ms: list[float] = []
    scan_ms: list[float] = []
    for query in golden.queries:
        embedding_started = time.perf_counter()
        query_vector = await embedder.embed_query(query.question)
        embedding_ms.append((time.perf_counter() - embedding_started) * 1_000)
        scan_started = time.perf_counter()
        index.search(query_vector, top_k=top_k, minimum_score=-1.0)
        scan_ms.append((time.perf_counter() - scan_started) * 1_000)
    return LatencyReport(
        chunk_count=index.chunk_count,
        index_build_ms=index_build_ms,
        query_embedding_ms=tuple(embedding_ms),
        scan_ms=tuple(scan_ms),
    )
