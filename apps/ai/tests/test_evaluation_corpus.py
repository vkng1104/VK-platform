import httpx
import pytest

from vk_ai.evaluation.corpus import corpus_transport, load_corpus_documents
from vk_ai.evaluation.dataset import EvaluationCorpus

_CORPUS = EvaluationCorpus.model_validate(
    {
        "version": 1,
        "description": "Test corpus.",
        "projects": [
            {
                "slug": "alpha",
                "title": "Alpha",
                "summary": "Alpha summary.",
                "period": "2024",
                "role": "Creator",
                "technologies": ["Go", "React"],
                "featured": True,
                "repository_url": "https://github.com/example/alpha",
                "live_url": None,
                "content_markdown": "## Design\n\nAlpha design notes.",
            }
        ],
    }
)


@pytest.mark.asyncio
async def test_transport_serves_list_and_detail_like_the_resource_api() -> None:
    async with httpx.AsyncClient(
        base_url="http://fixture.invalid", transport=corpus_transport(_CORPUS)
    ) as client:
        listing = await client.get("/api/v1/projects")
        detail = await client.get("/api/v1/projects/alpha")
        unknown = await client.get("/api/v1/projects/missing")
        write = await client.post("/api/v1/projects")

    assert listing.status_code == 200
    assert "content_markdown" not in listing.json()["projects"][0]
    assert detail.json()["project"]["content_markdown"] == "## Design\n\nAlpha design notes."
    assert (unknown.status_code, write.status_code) == (404, 405)


@pytest.mark.asyncio
async def test_documents_are_built_through_the_production_loader() -> None:
    documents = await load_corpus_documents(_CORPUS)

    assert len(documents) == 1
    document = documents[0]
    assert (document.source_id, document.source_url) == ("project:alpha", "/projects/alpha")
    assert document.content.startswith("# Alpha\n\nAlpha summary.")
    assert "Technologies: Go, React" in document.content
    assert document.content.endswith("## Design\n\nAlpha design notes.")
