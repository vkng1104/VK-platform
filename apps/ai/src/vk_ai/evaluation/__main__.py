"""Run the retrieval evaluation against a local Ollama model.

Example: python -m vk_ai.evaluation --chunk-sizes 600,1200 --overlaps 0,120
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from dataclasses import dataclass
from pathlib import Path

import httpx

from vk_ai.evaluation.corpus import load_corpus_documents
from vk_ai.evaluation.dataset import (
    DEFAULT_CORPUS_PATH,
    DEFAULT_GOLDEN_QUERIES_PATH,
    load_corpus,
    load_golden_queries,
)
from vk_ai.evaluation.report import render_json, render_markdown
from vk_ai.evaluation.runner import (
    TITLE_MODES,
    CachingEmbedder,
    Variant,
    VariantResult,
    evaluate_variant,
    measure_latency,
)
from vk_ai.retrieval.embedder import (
    INPUT_FORMAT_AUTO,
    INPUT_FORMATS,
    EmbeddingError,
    OllamaEmbeddingProvider,
)

_DEFAULT_MODEL = "embeddinggemma:300m"
_DEFAULT_OLLAMA_URL = "http://localhost:11434"
_MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class Options:
    ollama_url: str
    model: str
    corpus: Path
    golden: Path
    chunk_sizes: tuple[int, ...]
    overlaps: tuple[int, ...]
    input_formats: tuple[str, ...]
    title_modes: tuple[str, ...]
    default_chunk_size: int
    default_overlap: int
    batch_size: int
    timeout_seconds: float
    output_format: str
    measure_latency: bool


def parse_options(argv: list[str] | None = None) -> Options:
    parser = argparse.ArgumentParser(prog="python -m vk_ai.evaluation", description=__doc__)
    parser.add_argument(
        "--ollama-url",
        default=os.getenv("AI_EMBEDDING_BASE_URL") or _DEFAULT_OLLAMA_URL,
    )
    parser.add_argument("--model", default=os.getenv("AI_EMBEDDING_MODEL") or _DEFAULT_MODEL)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS_PATH)
    parser.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_QUERIES_PATH)
    parser.add_argument("--chunk-sizes", default="600,1200,2000")
    parser.add_argument("--overlaps", default="0,120,240")
    parser.add_argument("--input-formats", default="embeddinggemma,raw")
    parser.add_argument("--title-modes", default=",".join(TITLE_MODES))
    parser.add_argument("--default-chunk-size", type=int, default=1_200)
    parser.add_argument("--default-overlap", type=int, default=120)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--timeout-seconds", type=float, default=60.0)
    parser.add_argument("--format", dest="output_format", choices=("markdown", "json"))
    parser.add_argument("--skip-latency", action="store_true")
    args = parser.parse_args(argv)

    formats = _names(str(args.input_formats), INPUT_FORMATS, "--input-formats")
    if INPUT_FORMAT_AUTO in formats:
        parser.error("--input-formats must name explicit formats, not auto")
    return Options(
        ollama_url=str(args.ollama_url).rstrip("/"),
        model=str(args.model).strip(),
        corpus=Path(args.corpus),
        golden=Path(args.golden),
        chunk_sizes=_positive_ints(str(args.chunk_sizes), "--chunk-sizes", allow_zero=False),
        overlaps=_positive_ints(str(args.overlaps), "--overlaps", allow_zero=True),
        input_formats=formats,
        title_modes=_names(str(args.title_modes), TITLE_MODES, "--title-modes"),
        default_chunk_size=int(args.default_chunk_size),
        default_overlap=int(args.default_overlap),
        batch_size=int(args.batch_size),
        timeout_seconds=float(args.timeout_seconds),
        output_format=str(args.output_format or "markdown"),
        measure_latency=not bool(args.skip_latency),
    )


async def run(options: Options) -> str:
    corpus = load_corpus(options.corpus)
    golden = load_golden_queries(options.golden)
    documents = await load_corpus_documents(corpus)

    async with httpx.AsyncClient(
        base_url=options.ollama_url,
        timeout=httpx.Timeout(options.timeout_seconds),
        follow_redirects=False,
    ) as client:

        def provider(input_format: str) -> OllamaEmbeddingProvider:
            return OllamaEmbeddingProvider(
                client,
                model=options.model,
                batch_size=options.batch_size,
                maximum_response_bytes=_MAXIMUM_RESPONSE_BYTES,
                input_format=input_format,
            )

        runtime_default = provider(INPUT_FORMAT_AUTO)
        default_variant = Variant(
            options.default_chunk_size,
            options.default_overlap,
            runtime_default.input_format,
            TITLE_MODES[0],
        )
        results: list[VariantResult] = []
        for input_format in options.input_formats:
            embedder = CachingEmbedder(provider(input_format))
            for chunk_size in options.chunk_sizes:
                for overlap in options.overlaps:
                    if overlap >= chunk_size:
                        continue
                    for title_mode in options.title_modes:
                        variant = Variant(chunk_size, overlap, input_format, title_mode)
                        results.append(await evaluate_variant(documents, golden, embedder, variant))
        latency = (
            await measure_latency(documents, golden, runtime_default, default_variant)
            if options.measure_latency
            else None
        )

    render = render_json if options.output_format == "json" else render_markdown
    return render(
        model=options.model,
        project_count=len(corpus.projects),
        results=results,
        default_variant=default_variant,
        latency=latency,
    )


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(
        level=logging.WARNING, stream=sys.stderr, format="%(levelname)s %(message)s"
    )
    options = parse_options(argv)
    try:
        output = asyncio.run(run(options))
    except EmbeddingError as error:
        sys.stderr.write(
            f"Embedding failed: {error}.\n"
            f"Check that Ollama is running at {options.ollama_url} and that the model is "
            f"available (ollama pull {options.model}).\n"
        )
        return 2
    sys.stdout.write(output)
    return 0


def _positive_ints(raw: str, flag: str, *, allow_zero: bool) -> tuple[int, ...]:
    try:
        values = tuple(int(part) for part in raw.split(",") if part.strip())
    except ValueError as error:
        raise SystemExit(f"{flag} must be a comma-separated list of integers") from error
    minimum = 0 if allow_zero else 1
    if not values or any(value < minimum for value in values):
        raise SystemExit(f"{flag} must list integers of at least {minimum}")
    return values


def _names(raw: str, allowed: tuple[str, ...], flag: str) -> tuple[str, ...]:
    values = tuple(part.strip() for part in raw.split(",") if part.strip())
    if not values or any(value not in allowed for value in values):
        raise SystemExit(f"{flag} must list values from: {', '.join(allowed)}")
    return values


if __name__ == "__main__":
    raise SystemExit(main())
