import json

import httpx
import pytest

from vk_ai.retrieval.embedder import EmbeddingError, OllamaEmbeddingProvider


@pytest.mark.asyncio
async def test_ollama_adapter_batches_documents_and_maps_vectors() -> None:
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
        vectors = await provider.embed_documents(["one", "two", "three"])

    assert batches == [["one", "two"], ["three"]]
    assert vectors == [(1.0, 0.0), (1.0, 0.0), (1.0, 0.0)]


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
