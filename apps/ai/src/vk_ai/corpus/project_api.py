from __future__ import annotations

import hashlib
import json
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, TypeAdapter

from vk_ai.corpus.model import Document


class CorpusLoadError(RuntimeError):
    """Raised when published project content cannot be loaded safely."""


class _ProjectSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    slug: str
    title: str
    summary: str
    period: str
    role: str
    technologies: list[str]
    featured: bool
    repository_url: str | None
    live_url: str | None


class _ProjectDetail(_ProjectSummary):
    content_markdown: str


class _ProjectList(BaseModel):
    model_config = ConfigDict(extra="forbid")

    projects: list[_ProjectSummary]


class _ProjectEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project: _ProjectDetail


_project_list_adapter = TypeAdapter(_ProjectList)
_project_envelope_adapter = TypeAdapter(_ProjectEnvelope)


class ProjectAPI:
    def __init__(self, client: httpx.AsyncClient, maximum_response_bytes: int) -> None:
        if maximum_response_bytes <= 0:
            raise ValueError("maximum_response_bytes must be greater than zero")
        self._client = client
        self._maximum_response_bytes = maximum_response_bytes

    async def load_documents(self) -> list[Document]:
        listing = _project_list_adapter.validate_python(await self._get_json("/api/v1/projects"))
        documents: list[Document] = []
        for summary in listing.projects:
            envelope = _project_envelope_adapter.validate_python(
                await self._get_json(f"/api/v1/projects/{summary.slug}")
            )
            project = envelope.project
            if project.slug != summary.slug:
                raise CorpusLoadError("project detail slug did not match the project list")
            content = _canonical_content(project)
            documents.append(
                Document(
                    source_id=f"project:{project.slug}",
                    title=project.title,
                    source_url=f"/projects/{project.slug}",
                    content=content,
                    content_hash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
                )
            )
        return documents

    async def _get_json(self, path: str) -> Any:
        try:
            async with self._client.stream(
                "GET", path, headers={"accept": "application/json"}
            ) as response:
                response.raise_for_status()
                body = bytearray()
                async for block in response.aiter_bytes():
                    body.extend(block)
                    if len(body) > self._maximum_response_bytes:
                        raise CorpusLoadError("project API response exceeded the configured limit")
        except (httpx.HTTPError, OSError) as error:
            raise CorpusLoadError("project API request failed") from error

        try:
            return json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise CorpusLoadError("project API returned invalid JSON") from error


def _canonical_content(project: _ProjectDetail) -> str:
    technologies = ", ".join(project.technologies)
    return (
        f"# {project.title}\n\n"
        f"{project.summary.strip()}\n\n"
        f"Period: {project.period.strip()}\n\n"
        f"Role: {project.role.strip()}\n\n"
        f"Technologies: {technologies}\n\n"
        f"{project.content_markdown.strip()}"
    ).strip()
