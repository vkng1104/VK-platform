import json
import logging
from collections.abc import AsyncIterator

import httpx
import pytest

from vk_ai.retrieval.embedder import (
    EmbeddingDocument,
    EmbeddingError,
    OllamaEmbeddingProvider,
)

_QUERY = "Which language is the API written in?"
_PROMPTED_QUERY = f"task: search result | query: {_QUERY}"


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
                EmbeddingDocument(text="A document with a blank title.", title="   "),
            ]
        )
        await provider.embed_query(_QUERY)

    assert inputs == [
        [
            "title: VK Platform | text: The API is written in Go.",
            "title: none | text: A document without a title.",
        ],
        ["title: none | text: A document with a blank title."],
        [_PROMPTED_QUERY],
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("model", "expected_input", "expected_format", "expects_warning"),
    [
        ("embeddinggemma", _PROMPTED_QUERY, "embeddinggemma", False),
        ("EmbeddingGemma:latest", _PROMPTED_QUERY, "embeddinggemma", False),
        ("library/embeddinggemma:300m", _PROMPTED_QUERY, "embeddinggemma", False),
        (
            "registry.ollama.ai/library/embeddinggemma:300m-qat-q4_0",
            _PROMPTED_QUERY,
            "embeddinggemma",
            False,
        ),
        ("nomic-embed-text", _QUERY, "raw", False),
        ("hf.co/unsloth/embeddinggemma-300m-GGUF:Q8_0", _QUERY, "raw", True),
    ],
)
async def test_ollama_adapter_selects_and_reports_input_format_from_model_name(
    model: str,
    expected_input: str,
    expected_format: str,
    expects_warning: bool,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="vk_ai.retrieval.embedder")
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
            model=model,
            batch_size=2,
            maximum_response_bytes=10_000,
        )
        await provider.embed_query(_QUERY)

    assert inputs == [[expected_input]]
    expected_levels = [logging.INFO, logging.WARNING] if expects_warning else [logging.INFO]
    records = [record for record in caplog.records if record.name == "vk_ai.retrieval.embedder"]
    assert [record.levelno for record in records] == expected_levels
    for record in records:
        assert record.__dict__["embedding_model"] == model
        assert record.__dict__["embedding_input_format"] == expected_format


@pytest.mark.asyncio
@pytest.mark.parametrize("model", ["embeddinggemma:300m", "embedding-model"])
async def test_ollama_adapter_rejects_blank_inputs_before_sending(model: str) -> None:
    requests_sent = 0

    async def handler(_: httpx.Request) -> httpx.Response:
        nonlocal requests_sent
        requests_sent += 1
        return httpx.Response(200, json={"embeddings": [[1.0, 0.0]]})

    async with httpx.AsyncClient(
        base_url="http://ollama.example", transport=httpx.MockTransport(handler)
    ) as client:
        provider = OllamaEmbeddingProvider(
            client,
            model=model,
            batch_size=2,
            maximum_response_bytes=10_000,
        )
        with pytest.raises(EmbeddingError, match="non-empty text"):
            await provider.embed_query("   ")
        with pytest.raises(EmbeddingError, match="non-empty text"):
            await provider.embed_documents(
                [
                    EmbeddingDocument(text="A valid document."),
                    EmbeddingDocument(text=" \n ", title="VK Platform"),
                ]
            )

    assert requests_sent == 0


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
