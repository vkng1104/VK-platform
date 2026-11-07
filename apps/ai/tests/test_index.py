import pytest

from vk_ai.corpus.model import Chunk
from vk_ai.retrieval.index import VectorIndex


def chunk(chunk_id: str) -> Chunk:
    return Chunk(
        chunk_id=chunk_id,
        source_id="project:vk-platform",
        title="VK Platform",
        heading="Architecture",
        source_url="/projects/vk-platform",
        content_hash="hash",
        text=f"Evidence from {chunk_id}",
    )


def test_cosine_search_sorts_by_score_then_stable_chunk_id() -> None:
    index = VectorIndex.build(
        [chunk("project:z"), chunk("project:a"), chunk("project:orthogonal")],
        [(1.0, 0.0), (1.0, 0.0), (0.0, 1.0)],
    )

    matches = index.search((1.0, 0.0), top_k=3, minimum_score=0.0)

    assert [match.chunk_id for match in matches] == [
        "project:a",
        "project:z",
        "project:orthogonal",
    ]
    assert [match.score for match in matches] == pytest.approx([1.0, 1.0, 0.0])


def test_cosine_search_applies_threshold_before_top_k_and_skips_zero_norm() -> None:
    index = VectorIndex.build(
        [chunk("positive"), chunk("negative"), chunk("zero")],
        [(1.0, 0.0), (-1.0, 0.0), (0.0, 0.0)],
    )

    matches = index.search((1.0, 0.0), top_k=10, minimum_score=0.1)

    assert [match.chunk_id for match in matches] == ["positive"]


def test_index_rejects_invalid_vector_dimensions_and_non_finite_values() -> None:
    with pytest.raises(ValueError, match="dimensions"):
        VectorIndex.build([chunk("one"), chunk("two")], [(1.0,), (1.0, 0.0)])

    with pytest.raises(ValueError, match="finite"):
        VectorIndex.build([chunk("one")], [(float("nan"),)])
