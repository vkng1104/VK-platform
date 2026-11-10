import hashlib
from collections.abc import Sequence

import pytest

from vk_ai.corpus.model import Document
from vk_ai.retrieval.embedder import EmbeddingDocument
from vk_ai.retrieval.model import Vector
from vk_ai.retrieval.service import RetrievalService


class MutableLoader:
    def __init__(self, documents: list[Document]) -> None:
        self.documents = documents
        self.error: Exception | None = None

    async def load_documents(self) -> list[Document]:
        if self.error is not None:
            raise self.error
        return self.documents


class FakeEmbedder:
    def __init__(self) -> None:
        self.document_calls = 0
        self.query_calls = 0
        self.documents: list[EmbeddingDocument] = []

    async def embed_documents(self, documents: Sequence[EmbeddingDocument]) -> Sequence[Vector]:
        self.document_calls += 1
        self.documents.extend(documents)
        return [(1.0, 0.0) for _ in documents]

    async def embed_query(self, text: str) -> Vector:
        self.query_calls += 1
        return (1.0, 0.0)


def project_document() -> Document:
    content = "# VK Platform\n\nThe API is written in Go."
    return Document(
        source_id="project:vk-platform",
        title="VK Platform",
        source_url="/projects/vk-platform",
        content=content,
        content_hash=hashlib.sha256(content.encode()).hexdigest(),
    )


@pytest.mark.asyncio
async def test_rebuild_publishes_a_complete_index_and_preserves_it_on_failure() -> None:
    loader = MutableLoader([project_document()])
    embedder = FakeEmbedder()
    service = RetrievalService(
        loader,
        embedder,
        chunk_max_chars=200,
        chunk_overlap_chars=20,
        minimum_score=0.0,
    )

    await service.rebuild()
    loader.error = RuntimeError("upstream failed")
    with pytest.raises(RuntimeError, match="upstream failed"):
        await service.rebuild()

    matches = await service.retrieve("Which language is the API written in?", 5)
    assert service.ready
    assert service.document_count == 1
    assert len(matches) == 1
    assert matches[0].source_url == "/projects/vk-platform"
    assert embedder.documents == [
        EmbeddingDocument(text="The API is written in Go.", title="VK Platform")
    ]


@pytest.mark.asyncio
async def test_empty_corpus_becomes_ready_without_calling_embedding_provider() -> None:
    loader = MutableLoader([])
    embedder = FakeEmbedder()
    service = RetrievalService(
        loader,
        embedder,
        chunk_max_chars=200,
        chunk_overlap_chars=20,
        minimum_score=0.0,
    )

    await service.rebuild()
    matches = await service.retrieve("anything", 5)

    assert service.ready
    assert service.chunk_count == 0
    assert matches == []
    assert embedder.document_calls == 0
    assert embedder.query_calls == 0
