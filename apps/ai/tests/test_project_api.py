import httpx
import pytest

from vk_ai.corpus.project_api import CorpusLoadError, ProjectAPI


def project_list() -> dict[str, object]:
    return {
        "projects": [
            {
                "slug": "vk-platform",
                "title": "VK Platform",
                "summary": "Systems portfolio",
                "period": "2026 — Present",
                "role": "Creator",
                "technologies": ["Go", "Python"],
                "featured": True,
                "repository_url": None,
                "live_url": None,
            }
        ]
    }


@pytest.mark.asyncio
async def test_project_api_builds_canonical_published_documents() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/projects":
            return httpx.Response(200, json=project_list())
        return httpx.Response(
            200,
            json={
                "project": {
                    **project_list()["projects"][0],  # type: ignore[index]
                    "content_markdown": "## Architecture\n\nGo calls Python over HTTP.",
                }
            },
        )

    async with httpx.AsyncClient(
        base_url="https://resource.example", transport=httpx.MockTransport(handler)
    ) as client:
        documents = await ProjectAPI(client, 10_000).load_documents()

    assert len(documents) == 1
    assert documents[0].source_id == "project:vk-platform"
    assert documents[0].source_url == "/projects/vk-platform"
    assert "Technologies: Go, Python" in documents[0].content
    assert "Go calls Python over HTTP." in documents[0].content
    assert len(documents[0].content_hash) == 64


@pytest.mark.asyncio
async def test_project_api_bounds_upstream_response_size() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"{" + b"x" * 200 + b"}")

    async with httpx.AsyncClient(
        base_url="https://resource.example", transport=httpx.MockTransport(handler)
    ) as client:
        with pytest.raises(CorpusLoadError, match="exceeded"):
            await ProjectAPI(client, 100).load_documents()
