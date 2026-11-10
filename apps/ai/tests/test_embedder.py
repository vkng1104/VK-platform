import json
from collections.abc import AsyncIterator

import httpx
import pytest

from vk_ai.retrieval.embedder import (
    EmbeddingDocument,
    EmbeddingError,
    OllamaEmbeddingProvider,
)


class ChunkedResponseStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes]) -> None:
        self._chunks = chunks
        self.chunks_read = 0

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for chunk in self._chunks:
            self.chunks_read += 1
            yield chunk

    async def aclose(self) -> None:
        pass


@pytest.mark.asyncio
async def test_ollama_adapter_batches_documents_and_preserves_raw_fallback() -> None:
    batches: list[list[str]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        batches.append(payload["input"])
        return httpx.Response(200, json={"embeddings": [[1.0, 0.0] for _ in payload["input"]]})

    async with httpx.AsyncClient(
        base_url="http://ollama.example", transport=httpx.MockTransport(handler)
    ) as client:
        provider = OllamaEmbeddingProvider(
            client,
            model="embedding-model",
            batch_size=2,
            maximum_response_bytes=10_000,
        )
        vectors = await provider.embed_documents(
            [
                EmbeddingDocument(text="one", title="First"),
                EmbeddingDocument(text="two", title="Second"),
                EmbeddingDocument(text="three", title="Third"),
            ]
        )

    assert batches == [["one", "two"], ["three"]]
    assert vectors == [(1.0, 0.0), (1.0, 0.0), (1.0, 0.0)]


@pytest.mark.asyncio
async def test_ollama_adapter_formats_embeddinggemma_retrieval_inputs() -> None:
    inputs: list[list[str]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        inputs.append(payload["input"])
        return httpx.Response(200, json={"embeddings": [[1.0, 0.0] for _ in payload["input"]]})

    async with httpx.AsyncClient(
        base_url="http://ollama.example", transport=httpx.MockTransport(handler)
    ) as client:
        provider = OllamaEmbeddingProvider(
            client,
            model="embeddinggemma:300m",
            batch_size=2,
            maximum_response_bytes=10_000,
        )
        await provider.embed_documents(
            [
                EmbeddingDocument(text="The API is written in Go.", title=" VK Platform "),
                EmbeddingDocument(text="A document without a title."),
            ]
        )
        await provider.embed_query("Which language is the API written in?")

    assert inputs == [
        [
            "title: VK Platform | text: The API is written in Go.",
            "title: none | text: A document without a title.",
        ],
        ["task: search result | query: Which language is the API written in?"],
    ]


@pytest.mark.asyncio
async def test_ollama_adapter_rejects_non_finite_vectors() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content='{"embeddings":[[NaN]]}')

    async with httpx.AsyncClient(
        base_url="http://ollama.example", transport=httpx.MockTransport(handler)
    ) as client:
        provider = OllamaEmbeddingProvider(
            client,
            model="embedding-model",
            batch_size=2,
            maximum_response_bytes=10_000,
        )
        with pytest.raises(EmbeddingError, match="invalid vector"):
            await provider.embed_query("query")


@pytest.mark.asyncio
async def test_ollama_adapter_stops_reading_responses_over_the_limit() -> None:
    stream = ChunkedResponseStream([b'{"embeddings":', b" " * 20, b"[[1.0, 0.0]]}"])

    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, stream=stream)

    async with httpx.AsyncClient(
        base_url="http://ollama.example", transport=httpx.MockTransport(handler)
    ) as client:
        provider = OllamaEmbeddingProvider(
            client,
            model="embedding-model",
            batch_size=2,
            maximum_response_bytes=20,
        )
        with pytest.raises(EmbeddingError, match="response exceeded"):
            await provider.embed_query("query")

    assert stream.chunks_read == 2
