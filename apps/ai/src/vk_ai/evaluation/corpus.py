from __future__ import annotations

import httpx

from vk_ai.corpus.model import Document
from vk_ai.corpus.project_api import ProjectAPI
from vk_ai.evaluation.dataset import EvaluationCorpus

_PROJECTS_PATH = "/api/v1/projects"
_FIXTURE_BASE_URL = "http://evaluation-corpus.invalid"


def corpus_transport(corpus: EvaluationCorpus) -> httpx.MockTransport:
    """Serve the corpus exactly like the resource API's published project endpoints."""

    projects = {project.slug: project for project in corpus.projects}

    def handle(request: httpx.Request) -> httpx.Response:
        if request.method != "GET":
            return httpx.Response(405)
        path = request.url.path
        if path == _PROJECTS_PATH:
            summaries = [
                project.model_dump(exclude={"content_markdown"}) for project in corpus.projects
            ]
            return httpx.Response(200, json={"projects": summaries})
        project = projects.get(path.removeprefix(f"{_PROJECTS_PATH}/"))
        if path.startswith(f"{_PROJECTS_PATH}/") and project is not None:
            return httpx.Response(200, json={"project": project.model_dump()})
        return httpx.Response(404)

    return httpx.MockTransport(handle)


async def load_corpus_documents(
    corpus: EvaluationCorpus, *, maximum_response_bytes: int = 4 * 1024 * 1024
) -> list[Document]:
    """Build documents through the production loader, so parsing matches the runtime."""

    async with httpx.AsyncClient(
        base_url=_FIXTURE_BASE_URL, transport=corpus_transport(corpus)
    ) as client:
        return await ProjectAPI(client, maximum_response_bytes).load_documents()
