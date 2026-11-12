from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

from vk_ai.evaluation.dataset import GoldenQuery
from vk_ai.retrieval.model import Match


@dataclass(frozen=True, slots=True)
class QueryOutcome:
    """How one golden query ranked against the full, unthresholded result list."""

    query_id: str
    in_scope: bool
    expected_rank: int | None
    expected_score: float | None
    top_score: float | None
    top_source: str | None


@dataclass(frozen=True, slots=True)
class RetrievalMetrics:
    in_scope_count: int
    out_of_scope_count: int
    top1_hit_rate: float
    top3_hit_rate: float
    mean_reciprocal_rank: float
    lowest_expected_score: float | None
    highest_out_of_scope_score: float | None


@dataclass(frozen=True, slots=True)
class ThresholdSuggestion:
    """A minimum score and how many golden queries it would handle correctly."""

    threshold: float
    kept_in_scope: int
    in_scope_count: int
    rejected_out_of_scope: int
    out_of_scope_count: int

    @property
    def separates_all(self) -> bool:
        return (
            self.kept_in_scope == self.in_scope_count
            and self.rejected_out_of_scope == self.out_of_scope_count
        )


def score_query(query: GoldenQuery, matches: Sequence[Match]) -> QueryOutcome:
    """Find the first expected chunk in ranked matches (1-based rank)."""

    expected = {(source.source_url, source.heading) for source in query.expected}
    expected_rank: int | None = None
    expected_score: float | None = None
    for position, match in enumerate(matches, start=1):
        if (match.source_url, match.heading) in expected:
            expected_rank = position
            expected_score = match.score
            break
    top = matches[0] if matches else None
    return QueryOutcome(
        query_id=query.id,
        in_scope=query.kind == "in_scope",
        expected_rank=expected_rank,
        expected_score=expected_score,
        top_score=top.score if top is not None else None,
        top_source=f"{top.source_url}#{top.heading}" if top is not None else None,
    )


def summarize(outcomes: Sequence[QueryOutcome]) -> RetrievalMetrics:
    in_scope = [outcome for outcome in outcomes if outcome.in_scope]
    out_of_scope = [outcome for outcome in outcomes if not outcome.in_scope]
    ranks = [outcome.expected_rank for outcome in in_scope]
    expected_scores = [o.expected_score for o in in_scope if o.expected_score is not None]
    out_of_scope_scores = [o.top_score for o in out_of_scope if o.top_score is not None]
    count = len(in_scope)
    return RetrievalMetrics(
        in_scope_count=count,
        out_of_scope_count=len(out_of_scope),
        top1_hit_rate=_rate(sum(rank == 1 for rank in ranks), count),
        top3_hit_rate=_rate(sum(rank is not None and rank <= 3 for rank in ranks), count),
        mean_reciprocal_rank=_rate(sum(1 / rank for rank in ranks if rank is not None), count),
        lowest_expected_score=min(expected_scores, default=None),
        highest_out_of_scope_score=max(out_of_scope_scores, default=None),
    )


def suggest_threshold(
    expected_scores: Sequence[float], out_of_scope_scores: Sequence[float]
) -> ThresholdSuggestion | None:
    """Pick the minimum score that keeps the most answers and rejects the most non-answers.

    An in-scope query is kept when its expected chunk scores at or above the threshold. An
    out-of-scope query is rejected when its best chunk scores below it. Candidates are the
    midpoints between neighbouring scores. Ties prefer keeping more in-scope queries, because
    an answer step can still decline a near miss but cannot recover a filtered-out answer;
    then the widest margin; then the lower threshold.
    """

    if not expected_scores or not out_of_scope_scores:
        return None
    if any(not math.isfinite(score) for score in (*expected_scores, *out_of_scope_scores)):
        raise ValueError("scores must be finite")

    points = sorted({*expected_scores, *out_of_scope_scores})
    candidates = [points[0] - 0.01]
    candidates += [(lower + upper) / 2 for lower, upper in zip(points, points[1:], strict=False)]
    candidates.append(points[-1] + 0.01)

    def rank(threshold: float) -> tuple[int, int, float, float]:
        kept = sum(score >= threshold for score in expected_scores)
        rejected = sum(score < threshold for score in out_of_scope_scores)
        margin = min(abs(point - threshold) for point in points)
        return kept + rejected, kept, margin, -threshold

    best: float = max(candidates, key=rank)
    threshold = round(best, 4)
    return ThresholdSuggestion(
        threshold=threshold,
        kept_in_scope=sum(score >= threshold for score in expected_scores),
        in_scope_count=len(expected_scores),
        rejected_out_of_scope=sum(score < threshold for score in out_of_scope_scores),
        out_of_scope_count=len(out_of_scope_scores),
    )


def percentile(values: Sequence[float], fraction: float) -> float:
    """Nearest-rank percentile for small latency samples."""

    if not values:
        raise ValueError("values must not be empty")
    if not 0 < fraction <= 1:
        raise ValueError("fraction must be in (0, 1]")
    ordered = sorted(values)
    return ordered[max(math.ceil(fraction * len(ordered)) - 1, 0)]


def _rate(numerator: float, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0
