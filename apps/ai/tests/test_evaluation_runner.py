import json
from collections.abc import Sequence

import pytest

from vk_ai.corpus.model import Chunk
from vk_ai.evaluation import __main__ as cli
from vk_ai.evaluation.corpus import load_corpus_documents
from vk_ai.evaluation.dataset import EvaluationCorpus, GoldenQuerySet
from vk_ai.evaluation.report import render_json, render_markdown
from vk_ai.evaluation.runner import (
    CachingEmbedder,
    Variant,
    embedding_documents,
    evaluate_variant,
    measure_latency,
)
from vk_ai.retrieval.embedder import EmbeddingDocument, EmbeddingError
from vk_ai.retrieval.model import Vector

_CORPUS = EvaluationCorpus.model_validate(
    {
        "version": 1,
        "description": "Test corpus.",
        "projects": [
            {
                "slug": "alpha",
                "title": "Alpha",
                "summary": "Summary.",
                "period": "2024",
                "role": "Creator",
                "technologies": ["Go"],
                "featured": False,
                "repository_url": None,
                "live_url": None,
                "content_markdown": "## Design\n\nDesign notes.\n\n## Deploy\n\nDeploy notes.",
            }
        ],
    }
)
_GOLDEN = GoldenQuerySet.model_validate(
    {
        "version": 1,
        "description": "Test queries.",
        "queries": [
            {
                "id": "design",
                "question": "How is it designed?",
                "kind": "in_scope",
                "expected": [{"source_url": "/projects/alpha", "heading": "Design"}],
            },
            {
                "id": "deploy-expects-design",
                "question": "How is it deployed?",
                "kind": "in_scope",
                "expected": [{"source_url": "/projects/alpha", "heading": "Design"}],
            },
            {"id": "weather", "question": "Will it rain?", "kind": "out_of_scope", "expected": []},
        ],
    }
)
_VARIANT = Variant(1_200, 120, "embeddinggemma", "title")


def _vector(text: str) -> Vector:
    lowered = text.lower()
    if "design" in lowered:
        return (1.0, 0.0, 0.0)
    if "deploy" in lowered:
        return (0.0, 1.0, 0.0)
    return (0.0, 0.0, 1.0)


class KeywordEmbedder:
    def __init__(self) -> None:
        self.document_batches: list[list[EmbeddingDocument]] = []
        self.queries: list[str] = []

    async def embed_documents(self, documents: Sequence[EmbeddingDocument]) -> Sequence[Vector]:
        self.document_batches.append(list(documents))
        return [_vector(document.text) for document in documents]

    async def embed_query(self, text: str) -> Vector:
        self.queries.append(text)
        return _vector(text)


@pytest.mark.asyncio
async def test_evaluate_variant_ranks_expected_chunks_without_a_threshold() -> None:
    documents = await load_corpus_documents(_CORPUS)

    result = await evaluate_variant(documents, _GOLDEN, KeywordEmbedder(), _VARIANT)

    outcomes = {outcome.query_id: outcome for outcome in result.outcomes}
    assert result.chunk_count == 3
    assert (outcomes["design"].expected_rank, outcomes["design"].expected_score) == (1, 1.0)
    assert outcomes["deploy-expects-design"].expected_rank == 3
    assert outcomes["deploy-expects-design"].top_source == "/projects/alpha#Deploy"
    assert outcomes["weather"].top_source == "/projects/alpha#Alpha"
    assert result.metrics.top1_hit_rate == 0.5
    assert result.metrics.mean_reciprocal_rank == pytest.approx((1 + 1 / 3) / 2)
    assert result.threshold is not None


@pytest.mark.asyncio
async def test_caching_embedder_embeds_each_unique_input_once() -> None:
    inner = KeywordEmbedder()
    embedder = CachingEmbedder(inner)
    first = EmbeddingDocument(text="Design notes.", title="Alpha")
    second = EmbeddingDocument(text="Deploy notes.", title="Alpha")

    vectors = await embedder.embed_documents([first, second, first])
    again = await embedder.embed_documents([second, first])
    await embedder.embed_query("How is it designed?")
    await embedder.embed_query("How is it designed?")

    assert vectors == [(1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (1.0, 0.0, 0.0)]
    assert again == [(0.0, 1.0, 0.0), (1.0, 0.0, 0.0)]
    assert inner.document_batches == [[first, second]]
    assert inner.queries == ["How is it designed?"]


def test_embedding_documents_add_the_heading_only_in_title_heading_mode() -> None:
    chunks = [
        Chunk("a:0", "a", "Alpha", "Alpha", "/projects/a", "h", "Intro."),
        Chunk("a:1", "a", "Alpha", "Design", "/projects/a", "h", "Design notes."),
    ]

    titles = [document.title for document in embedding_documents(chunks, "title")]
    headed = [document.title for document in embedding_documents(chunks, "title-heading")]

    assert titles == ["Alpha", "Alpha"]
    assert headed == ["Alpha", "Alpha — Design"]
    with pytest.raises(ValueError, match="title_mode"):
        embedding_documents(chunks, "heading")


@pytest.mark.parametrize(
    ("arguments", "message"),
    [((100, 100, "raw", "title"), "overlap"), ((100, 10, "raw", "heading"), "title_mode")],
)
def test_variant_rejects_invalid_settings(
    arguments: tuple[int, int, str, str], message: str
) -> None:
    with pytest.raises(ValueError, match=message):
        Variant(*arguments)


@pytest.mark.asyncio
async def test_measure_latency_warms_up_then_times_every_query() -> None:
    documents = await load_corpus_documents(_CORPUS)
    embedder = KeywordEmbedder()

    latency = await measure_latency(documents, _GOLDEN, embedder, _VARIANT)

    assert latency.chunk_count == 3
    assert len(latency.query_embedding_ms) == len(latency.scan_ms) == 3
    assert embedder.queries[0] == "warm-up"
    assert latency.scan_median_ms >= 0


@pytest.mark.asyncio
async def test_reports_mark_the_default_variant_and_include_latency() -> None:
    documents = await load_corpus_documents(_CORPUS)
    result = await evaluate_variant(documents, _GOLDEN, KeywordEmbedder(), _VARIANT)
    latency = await measure_latency(documents, _GOLDEN, KeywordEmbedder(), _VARIANT)
    markdown = render_markdown(
        model="embeddinggemma:300m",
        project_count=1,
        results=[result],
        default_variant=_VARIANT,
        latency=latency,
    )
    payload = json.loads(
        render_json(
            model="embeddinggemma:300m",
            project_count=1,
            results=[result],
            default_variant=_VARIANT,
            latency=latency,
        )
    )

    assert "| 1200/120 embeddinggemma title (default) | 3 | 50% |" in markdown
    assert "Index build for 3 chunks" in markdown
    assert payload["default_variant"]["chunk_max_chars"] == 1_200
    assert payload["results"][0]["metrics"]["top1_hit_rate"] == 0.5


def test_cli_rejects_auto_as_a_swept_input_format() -> None:
    with pytest.raises(SystemExit):
        cli.parse_options(["--input-formats", "auto"])


def test_cli_rejects_invalid_chunk_sizes() -> None:
    with pytest.raises(SystemExit, match="--chunk-sizes"):
        cli.parse_options(["--chunk-sizes", "0,1200"])


def test_cli_reports_provider_failures_with_a_setup_hint(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    async def failing_run(_: cli.Options) -> str:
        raise EmbeddingError("embedding provider request failed")

    monkeypatch.setattr(cli, "run", failing_run)

    exit_code = cli.main(["--model", "embeddinggemma:300m"])

    assert exit_code == 2
    assert "ollama pull embeddinggemma:300m" in capsys.readouterr().err
