from __future__ import annotations

import httpx
from fastapi import FastAPI

from vk_ai.config import Settings
from vk_ai.corpus.project_api import ProjectAPI
from vk_ai.retrieval.embedder import OllamaEmbeddingProvider
from vk_ai.retrieval.service import RetrievalService
from vk_ai.transport.http import create_app


def create_default_app() -> FastAPI:
    settings = Settings.from_env()
    timeout = httpx.Timeout(settings.upstream_timeout_seconds)
    resource_client = httpx.AsyncClient(
        base_url=settings.resource_api_base_url,
        timeout=timeout,
        follow_redirects=False,
    )
    embedding_client = httpx.AsyncClient(
        base_url=settings.embedding_base_url,
        timeout=timeout,
        follow_redirects=False,
    )
    loader = ProjectAPI(resource_client, settings.maximum_response_bytes)
    embedder = OllamaEmbeddingProvider(
        embedding_client,
        model=settings.embedding_model,
        batch_size=settings.embedding_batch_size,
        maximum_response_bytes=settings.maximum_response_bytes,
    )
    service = RetrievalService(
        loader,
        embedder,
        chunk_max_chars=settings.chunk_max_chars,
        chunk_overlap_chars=settings.chunk_overlap_chars,
        minimum_score=settings.retrieval_min_score,
    )
    return create_app(
        settings,
        service,
        closeables=(resource_client, embedding_client),
    )
