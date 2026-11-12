import re

import pytest
from pydantic import ValidationError

from vk_ai.corpus.chunker import chunk_documents
from vk_ai.evaluation.corpus import load_corpus_documents
from vk_ai.evaluation.dataset import (
    DEFAULT_CORPUS_PATH,
    EvaluationCorpus,
    GoldenQuerySet,
    load_corpus,
    load_golden_queries,
)

_PROJECT = {
    "slug": "alpha",
    "title": "Alpha",
    "summary": "Summary.",
    "period": "2024",
    "role": "Creator",
    "technologies": ["Go"],
    "featured": False,
    "repository_url": None,
    "live_url": None,
    "content_markdown": "## Section\n\nText.",
}

# CV-only, client, contact and secret material must never enter the public corpus.
_FORBIDDEN_CORPUS_PATTERNS = [
    r"ChargeFUZE",
    r"Numerator",
    r"\bBuck\b",
    r"Stripe",
    r"CBORD",
    r"Spartan",
    r"c0x12c",
    r"[\w.+-]+@[\w-]+\.[\w.]+",
    r"\+84",
    r"\b\d{3}[ -]?\d{3}[ -]?\d{3,4}\b",
    r"\beyJ[\w-]{10,}",
    r"(?i)bearer\s+\S{12,}",
]


def test_published_corpus_and_golden_queries_load() -> None:
    corpus = load_corpus()
    golden = load_golden_queries()

    assert {project.slug for project in corpus.projects} == {
        "vk-platform",
        "bittorrent-network-dashboard",
        "music-nft-marketplace",
        "trie-kmp-dictionary",
    }
    kinds = [query.kind for query in golden.queries]
    assert kinds.count("in_scope") >= 15
    assert kinds.count("out_of_scope") >= 3


@pytest.mark.asyncio
@pytest.mark.parametrize("max_chars", [600, 1200, 2000])
async def test_every_expected_source_exists_at_each_swept_chunk_size(max_chars: int) -> None:
    documents = await load_corpus_documents(load_corpus())
    chunks = chunk_documents(documents, max_chars=max_chars, overlap_chars=0)
    available = {(chunk.source_url, chunk.heading) for chunk in chunks}

    missing = [
        (query.id, source.source_url, source.heading)
        for query in load_golden_queries().queries
        for source in query.expected
        if (source.source_url, source.heading) not in available
    ]

    assert missing == []


def test_published_corpus_contains_no_private_or_secret_material() -> None:
    text = DEFAULT_CORPUS_PATH.read_text(encoding="utf-8")

    found = [pattern for pattern in _FORBIDDEN_CORPUS_PATTERNS if re.search(pattern, text)]

    assert found == []


def test_corpus_rejects_duplicate_slugs() -> None:
    with pytest.raises(ValidationError, match="unique"):
        EvaluationCorpus.model_validate(
            {"version": 1, "description": "", "projects": [_PROJECT, _PROJECT]}
        )


@pytest.mark.parametrize(
    ("query", "message"),
    [
        ({"id": "a", "question": "q?", "kind": "in_scope", "expected": []}, "at least one"),
        (
            {
                "id": "a",
                "question": "q?",
                "kind": "out_of_scope",
                "expected": [{"source_url": "/projects/alpha", "heading": "Section"}],
            },
            "must not expect",
        ),
    ],
)
def test_golden_queries_match_expectations_to_their_kind(
    query: dict[str, object], message: str
) -> None:
    with pytest.raises(ValidationError, match=message):
        GoldenQuerySet.model_validate({"version": 1, "description": "", "queries": [query]})


def test_golden_queries_reject_duplicate_ids() -> None:
    query = {"id": "a", "question": "q?", "kind": "out_of_scope", "expected": []}

    with pytest.raises(ValidationError, match="unique"):
        GoldenQuerySet.model_validate({"version": 1, "description": "", "queries": [query, query]})
