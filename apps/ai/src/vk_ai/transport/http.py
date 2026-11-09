from __future__ import annotations

import logging
import secrets
from collections.abc import AsyncGenerator, Sequence
from contextlib import asynccontextmanager
from typing import Protocol

from fastapi import FastAPI, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from vk_ai.config import Settings
from vk_ai.retrieval.embedder import EmbeddingError
from vk_ai.retrieval.model import Match
from vk_ai.retrieval.service import RuntimeNotReadyError

logger = logging.getLogger(__name__)


class RuntimeService(Protocol):
    @property
    def ready(self) -> bool: ...

    @property
    def document_count(self) -> int: ...

    @property
    def chunk_count(self) -> int: ...

    async def rebuild(self) -> None: ...

    async def retrieve(self, query: str, top_k: int) -> Sequence[Match]: ...


class AsyncCloseable(Protocol):
    async def aclose(self) -> None: ...


class RetrievalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=500)
    top_k: int = Field(default=5, ge=1, le=10)


class MatchResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    chunk_id: str
    title: str
    heading: str
    excerpt: str
    source_url: str
    score: float


class RetrievalResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    matches: list[MatchResponse]


def create_app(
    settings: Settings,
    service: RuntimeService,
    *,
    closeables: Sequence[AsyncCloseable] = (),
) -> FastAPI:
    expected_authorization = f"Bearer {settings.internal_token}".encode()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncGenerator[None]:
        try:
            await service.rebuild()
        except Exception:
            logger.exception("retrieval index build failed")
        try:
            yield
        finally:
            for closeable in closeables:
                await closeable.aclose()

    app = FastAPI(
        title="VK Platform AI Runtime",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    @app.get("/healthz")
    async def health() -> dict[str, str]:
        return {"status": "ok", "service": "ai"}

    @app.get("/readyz")
    async def readiness() -> dict[str, str | int]:
        if not service.ready:
            raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="not ready")
        return {
            "status": "ready",
            "service": "ai",
            "document_count": service.document_count,
            "chunk_count": service.chunk_count,
        }

    @app.post("/internal/v1/retrievals", response_model=RetrievalResponse)
    async def retrieve(
        request: RetrievalRequest,
        authorization: str | None = Header(default=None),
    ) -> RetrievalResponse:
        if authorization is None or not secrets.compare_digest(
            authorization.encode(), expected_authorization
        ):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized")
        query = request.query.strip()
        if not query:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="empty query"
            )
        try:
            matches = await service.retrieve(query, request.top_k)
        except RuntimeNotReadyError as error:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="not ready"
            ) from error
        except EmbeddingError as error:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="provider unavailable"
            ) from error
        return RetrievalResponse(
            matches=[
                MatchResponse(
                    chunk_id=match.chunk_id,
                    title=match.title,
                    heading=match.heading,
                    excerpt=match.excerpt,
                    source_url=match.source_url,
                    score=match.score,
                )
                for match in matches
            ]
        )

    return app
