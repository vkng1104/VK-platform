from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager

import httpx
import pytest

from vk_ai.config import Settings
from vk_ai.retrieval.model import Match
from vk_ai.transport.http import create_app


class FakeService:
    def __init__(self, *, ready_after_rebuild: bool = True) -> None:
        self._ready = False
        self._ready_after_rebuild = ready_after_rebuild

    @property
    def ready(self) -> bool:
        return self._ready

    @property
    def document_count(self) -> int:
        return 1 if self._ready else 0

    @property
    def chunk_count(self) -> int:
        return 2 if self._ready else 0

    async def rebuild(self) -> None:
        self._ready = self._ready_after_rebuild

    async def retrieve(self, query: str, top_k: int) -> Sequence[Match]:
        return [
            Match(
                chunk_id="project:vk-platform:architecture:0",
                title="VK Platform",
                heading="Architecture",
                excerpt="Go calls a private Python runtime.",
                source_url="/projects/vk-platform",
                score=0.91,
            )
        ][:top_k]


def settings() -> Settings:
    return Settings(
        internal_token="private-token",
        resource_api_base_url="http://api:8080",
        embedding_base_url="http://ollama:11434",
        embedding_model="embedding-model",
    )


@asynccontextmanager
async def runtime_client(service: FakeService) -> AsyncIterator[httpx.AsyncClient]:
    app = create_app(settings(), service)
    transport = httpx.ASGITransport(app=app)
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(transport=transport, base_url="http://ai.test") as client,
    ):
        yield client


@pytest.mark.asyncio
async def test_private_retrieval_requires_token_and_rejects_unknown_fields() -> None:
    async with runtime_client(FakeService()) as client:
        unauthorized = await client.post(
            "/internal/v1/retrievals", json={"query": "architecture", "top_k": 1}
        )
        invalid = await client.post(
            "/internal/v1/retrievals",
            headers={"authorization": "Bearer private-token"},
            json={"query": "architecture", "unexpected": True},
        )

    assert unauthorized.status_code == 401
    assert invalid.status_code == 422


@pytest.mark.asyncio
async def test_private_retrieval_rejects_non_ascii_authorization() -> None:
    async with runtime_client(FakeService()) as client:
        response = await client.post(
            "/internal/v1/retrievals",
            headers={"authorization": b"Bearer caf\xe9"},
            json={"query": "architecture", "top_k": 1},
        )

    assert response.status_code == 401


@pytest.mark.asyncio
async def test_private_retrieval_returns_structured_matches_when_ready() -> None:
    async with runtime_client(FakeService()) as client:
        readiness = await client.get("/readyz")
        response = await client.post(
            "/internal/v1/retrievals",
            headers={"authorization": "Bearer private-token"},
            json={"query": "architecture", "top_k": 1},
        )

    assert readiness.status_code == 200
    assert readiness.json()["chunk_count"] == 2
    assert response.status_code == 200
    assert response.json()["matches"][0]["chunk_id"] == "project:vk-platform:architecture:0"


@pytest.mark.asyncio
async def test_readiness_fails_safely_without_a_complete_index() -> None:
    async with runtime_client(FakeService(ready_after_rebuild=False)) as client:
        response = await client.get("/readyz")

    assert response.status_code == 503
