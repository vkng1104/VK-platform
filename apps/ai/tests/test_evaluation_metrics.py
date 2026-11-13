import math

import pytest

from vk_ai.evaluation.dataset import ExpectedSource, GoldenQuery
from vk_ai.evaluation.metrics import (
    QueryOutcome,
    percentile,
    score_query,
    suggest_threshold,
    summarize,
)
from vk_ai.retrieval.model import Match


def _match(source_url: str, heading: str, score: float) -> Match:
    return Match(
        chunk_id=f"{source_url}:{heading}",
        title="Title",
        heading=heading,
        excerpt="text",
        source_url=source_url,
        score=score,
    )


def _in_scope(*expected: tuple[str, str]) -> GoldenQuery:
    return GoldenQuery(
        id="query",
        question="question?",
        kind="in_scope",
        expected=[ExpectedSource(source_url=url, heading=heading) for url, heading in expected],
    )


def test_score_query_ranks_the_first_expected_chunk_and_reports_the_top_match() -> None:
    query = _in_scope(("/projects/a", "Second"), ("/projects/a", "Third"))
    matches = [
        _match("/projects/b", "First", 0.9),
        _match("/projects/a", "Third", 0.8),
        _match("/projects/a", "Second", 0.7),
    ]

    outcome = score_query(query, matches)

    assert outcome.in_scope
    assert outcome.expected_rank == 2
    assert outcome.expected_score == 0.8
    assert outcome.top_score == 0.9
    assert outcome.top_source == "/projects/b#First"


def test_score_query_handles_missing_expectations_and_empty_results() -> None:
    query = _in_scope(("/projects/a", "Missing"))

    missed = score_query(query, [_match("/projects/a", "Other", 0.5)])
    empty = score_query(query, [])

    assert (missed.expected_rank, missed.expected_score) == (None, None)
    assert (empty.top_score, empty.top_source) == (None, None)


def test_summarize_computes_hit_rates_mrr_and_score_bounds() -> None:
    outcomes = [
        QueryOutcome("a", True, 1, 0.8, 0.8, "x"),
        QueryOutcome("b", True, 2, 0.6, 0.7, "y"),
        QueryOutcome("c", True, 4, 0.4, 0.9, "z"),
        QueryOutcome("d", True, None, None, 0.5, "w"),
        QueryOutcome("e", False, None, None, 0.3, "v"),
        QueryOutcome("f", False, None, None, 0.2, "u"),
    ]

    metrics = summarize(outcomes)

    assert (metrics.in_scope_count, metrics.out_of_scope_count) == (4, 2)
    assert metrics.top1_hit_rate == 0.25
    assert metrics.top3_hit_rate == 0.5
    assert metrics.mean_reciprocal_rank == pytest.approx((1 + 0.5 + 0.25) / 4)
    assert metrics.lowest_expected_score == 0.4
    assert metrics.highest_out_of_scope_score == 0.3


def test_summarize_reports_zero_rates_without_in_scope_queries() -> None:
    metrics = summarize([QueryOutcome("e", False, None, None, 0.3, "v")])

    assert (metrics.top1_hit_rate, metrics.mean_reciprocal_rank) == (0.0, 0.0)
    assert metrics.lowest_expected_score is None


def test_suggest_threshold_uses_the_midpoint_when_scores_separate() -> None:
    suggestion = suggest_threshold([0.62, 0.35, 0.58], [0.10, 0.18])

    assert suggestion is not None
    assert suggestion.threshold == pytest.approx((0.18 + 0.35) / 2, abs=1e-4)
    assert suggestion.separates_all


def test_suggest_threshold_maximizes_correct_decisions_when_scores_overlap() -> None:
    suggestion = suggest_threshold([0.35, 0.58, 0.63], [0.10, 0.18, 0.39])

    assert suggestion is not None
    assert suggestion.threshold == pytest.approx((0.18 + 0.35) / 2, abs=1e-4)
    assert (suggestion.kept_in_scope, suggestion.rejected_out_of_scope) == (3, 2)
    assert not suggestion.separates_all


def test_suggest_threshold_needs_both_kinds_and_finite_scores() -> None:
    assert suggest_threshold([], [0.1]) is None
    assert suggest_threshold([0.5], []) is None
    with pytest.raises(ValueError, match="finite"):
        suggest_threshold([math.nan], [0.1])


def test_percentile_uses_the_nearest_rank() -> None:
    values = [5.0, 1.0, 3.0, 2.0, 4.0]

    assert percentile(values, 0.5) == 3.0
    assert percentile(values, 0.95) == 5.0
    with pytest.raises(ValueError):
        percentile([], 0.5)
    with pytest.raises(ValueError):
        percentile(values, 0.0)
