from __future__ import annotations

import logging
import math
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

import httpx
from pydantic import BaseModel, ConfigDict

from vk_ai.retrieval.model import Vector

logger = logging.getLogger(__name__)

INPUT_FORMAT_AUTO = "auto"
INPUT_FORMATS = (INPUT_FORMAT_AUTO, "embeddinggemma", "raw")


class EmbeddingError(RuntimeError):
    """Raised when the configured embedding provider fails safely."""


@dataclass(frozen=True, slots=True)
class EmbeddingDocument:
    text: str
    title: str | None = None


class EmbeddingProvider(Protocol):
    async def embed_documents(self, documents: Sequence[EmbeddingDocument]) -> Sequence[Vector]: ...

    async def embed_query(self, text: str) -> Vector: ...


class _EmbeddingResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    embeddings: list[list[float]]


class _OllamaInputFormatter(Protocol):
    @property
    def name(self) -> str: ...

    def document(self, document: EmbeddingDocument) -> str: ...

    def query(self, text: str) -> str: ...


class _RawInputFormatter:
    name = "raw"

    def document(self, document: EmbeddingDocument) -> str:
        return document.text

    def query(self, text: str) -> str:
        return text


class _EmbeddingGemmaInputFormatter:
    name = "embeddinggemma"

    def document(self, document: EmbeddingDocument) -> str:
        title = document.title.strip() if document.title and document.title.strip() else "none"
        return f"title: {title} | text: {document.text}"

    def query(self, text: str) -> str:
        return f"task: search result | query: {text}"


def _input_formatter_for(model: str, input_format: str) -> _OllamaInputFormatter:
    if input_format == _EmbeddingGemmaInputFormatter.name:
        return _EmbeddingGemmaInputFormatter()
    if input_format == _RawInputFormatter.name:
        return _RawInputFormatter()
    if input_format != INPUT_FORMAT_AUTO:
        raise ValueError(f"input_format must be one of {', '.join(INPUT_FORMATS)}")
    untagged_model = model.rsplit("@", 1)[0].rsplit("/", 1)[-1].split(":", 1)[0]
    if untagged_model.casefold() == "embeddinggemma":
        return _EmbeddingGemmaInputFormatter()
    return _RawInputFormatter()


def _log_input_format(model: str, input_format: str, formatter: _OllamaInputFormatter) -> None:
    fields = {
        "embedding_model": model,
        "embedding_input_format": formatter.name,
        "embedding_input_format_setting": input_format,
    }
    logger.info("embedding input format selected: %s", formatter.name, extra=fields)
    if (
        input_format == INPUT_FORMAT_AUTO
        and formatter.name == _RawInputFormatter.name
        and "embeddinggemma" in model.casefold()
    ):
        logger.warning(
            "embedding model %r looks like EmbeddingGemma but is not a recognized "
            "EmbeddingGemma name; sending raw text without its retrieval prompts. "
            "Set AI_EMBEDDING_INPUT_FORMAT=embeddinggemma to apply them",
            model,
            extra=fields,
        )


class OllamaEmbeddingProvider:
    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        model: str,
        batch_size: int,
        maximum_response_bytes: int,
        input_format: str = INPUT_FORMAT_AUTO,
    ) -> None:
        if not model.strip():
            raise ValueError("model is required")
        if batch_size <= 0 or maximum_response_bytes <= 0:
            raise ValueError("batch_size and maximum_response_bytes must be greater than zero")
        self._client = client
        self._model = model.strip()
        self._batch_size = batch_size
        self._maximum_response_bytes = maximum_response_bytes
        self._input_formatter = _input_formatter_for(self._model, input_format)
        _log_input_format(self._model, input_format, self._input_formatter)

    @property
    def input_format(self) -> str:
        return self._input_formatter.name

    async def embed_documents(self, documents: Sequence[EmbeddingDocument]) -> Sequence[Vector]:
        texts = [self._format_document(document) for document in documents]
        vectors: list[Vector] = []
        for start in range(0, len(texts), self._batch_size):
            vectors.extend(await self._embed(list(texts[start : start + self._batch_size])))
        return vectors

    async def embed_query(self, text: str) -> Vector:
        if not text.strip():
            raise EmbeddingError("embedding input must contain non-empty text")
        vectors = await self._embed([self._input_formatter.query(text)])
        if len(vectors) != 1:
            raise EmbeddingError("embedding provider returned the wrong query vector count")
        return vectors[0]

    def _format_document(self, document: EmbeddingDocument) -> str:
        if not document.text.strip():
            raise EmbeddingError("embedding input must contain non-empty text")
        return self._input_formatter.document(document)

    async def _embed(self, texts: list[str]) -> list[Vector]:
        if not texts or any(not text.strip() for text in texts):
            raise EmbeddingError("embedding input must contain non-empty text")
        try:
            async with self._client.stream(
                "POST",
                "/api/embed",
                json={"model": self._model, "input": texts, "truncate": True},
            ) as response:
                response.raise_for_status()
                body = bytearray()
                async for block in response.aiter_bytes():
                    if len(body) + len(block) > self._maximum_response_bytes:
                        raise EmbeddingError(
                            "embedding provider response exceeded the configured limit"
                        )
                    body.extend(block)
        except httpx.HTTPError as error:
            raise EmbeddingError("embedding provider request failed") from error

        try:
            payload = _EmbeddingResponse.model_validate_json(body)
        except (ValueError, TypeError) as error:
            raise EmbeddingError("embedding provider returned an invalid response") from error
        if len(payload.embeddings) != len(texts):
            raise EmbeddingError("embedding provider returned the wrong vector count")

        vectors: list[Vector] = []
        for raw_vector in payload.embeddings:
            vector = tuple(float(value) for value in raw_vector)
            if not vector or any(not math.isfinite(value) for value in vector):
                raise EmbeddingError("embedding provider returned an invalid vector")
            vectors.append(vector)
        return vectors
