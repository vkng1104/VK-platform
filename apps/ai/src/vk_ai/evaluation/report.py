from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import asdict

from vk_ai.evaluation.runner import LatencyReport, Variant, VariantResult


def render_markdown(
    *,
    model: str,
    project_count: int,
    results: Sequence[VariantResult],
    default_variant: Variant,
    latency: LatencyReport | None,
) -> str:
    lines: list[str] = []
    first = results[0].metrics if results else None
    query_summary = (
        f"{first.in_scope_count} in-scope and {first.out_of_scope_count} out-of-scope queries"
        if first is not None
        else "no queries"
    )
    lines += [
        "# Retrieval evaluation",
        "",
        f"- Model: `{model}`",
        f"- Corpus: {project_count} projects; {query_summary}",
        "- Ranks use every chunk with no score threshold. The suggested threshold keeps an",
        "  in-scope query when its expected chunk scores at or above it, and rejects an",
        "  out-of-scope query when its best chunk scores below it.",
        "",
        "| Variant (chunk/overlap format title) | Chunks | Top-1 | Top-3 | MRR "
        "| Lowest expected | Highest out-of-scope | Threshold | Kept | Rejected |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for result in results:
        metrics = result.metrics
        threshold = result.threshold
        marker = " (default)" if result.variant == default_variant else ""
        kept = rejected = "-"
        if threshold is not None:
            kept = _fraction(threshold.kept_in_scope, threshold.in_scope_count)
            rejected = _fraction(threshold.rejected_out_of_scope, threshold.out_of_scope_count)
        lines.append(
            f"| {result.variant.label}{marker} | {result.chunk_count} "
            f"| {metrics.top1_hit_rate:.0%} | {metrics.top3_hit_rate:.0%} "
            f"| {metrics.mean_reciprocal_rank:.3f} "
            f"| {_score(metrics.lowest_expected_score)} "
            f"| {_score(metrics.highest_out_of_scope_score)} "
            f"| {_score(threshold.threshold if threshold else None)} "
            f"| {kept} | {rejected} |"
        )

    default_result = next((r for r in results if r.variant == default_variant), None)
    if default_result is not None:
        lines += ["", f"## Default variant details ({default_variant.label})", ""]
        lines += ["| Query | Kind | Expected rank | Expected score | Top score | Top match |"]
        lines += ["|---|---|---:|---:|---:|---|"]
        for outcome in default_result.outcomes:
            lines.append(
                f"| {outcome.query_id} | {'in' if outcome.in_scope else 'out'} "
                f"| {outcome.expected_rank if outcome.expected_rank is not None else '-'} "
                f"| {_score(outcome.expected_score)} | {_score(outcome.top_score)} "
                f"| {outcome.top_source or '-'} |"
            )

    if latency is not None:
        lines += [
            "",
            f"## Latency ({default_variant.label}, uncached, after one warm-up call)",
            "",
            f"- Index build for {latency.chunk_count} chunks: {latency.index_build_ms:.0f} ms",
            f"- Query embedding: median {latency.query_embedding_median_ms:.1f} ms, "
            f"p95 {latency.query_embedding_p95_ms:.1f} ms",
            f"- Linear scan: median {latency.scan_median_ms:.2f} ms, "
            f"p95 {latency.scan_p95_ms:.2f} ms",
        ]
    return "\n".join(lines) + "\n"


def render_json(
    *,
    model: str,
    project_count: int,
    results: Sequence[VariantResult],
    default_variant: Variant,
    latency: LatencyReport | None,
) -> str:
    payload = {
        "model": model,
        "project_count": project_count,
        "default_variant": asdict(default_variant),
        "results": [asdict(result) for result in results],
        "latency": asdict(latency) if latency is not None else None,
    }
    return json.dumps(payload, indent=2) + "\n"


def _score(value: float | None) -> str:
    return f"{value:.4f}" if value is not None else "-"


def _fraction(numerator: int, denominator: int) -> str:
    return f"{numerator}/{denominator}"
