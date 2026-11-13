from __future__ import annotations

from pathlib import Path
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

DATA_DIRECTORY = Path(__file__).resolve().parent / "data"
DEFAULT_CORPUS_PATH = DATA_DIRECTORY / "corpus.json"
DEFAULT_GOLDEN_QUERIES_PATH = DATA_DIRECTORY / "golden_queries.json"

QueryKind = Literal["in_scope", "out_of_scope"]


class CorpusProject(BaseModel):
    """One published project, in the resource API's project detail shape."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    slug: str = Field(min_length=1)
    title: str = Field(min_length=1)
    summary: str
    period: str
    role: str
    technologies: list[str]
    featured: bool
    repository_url: str | None
    live_url: str | None
    content_markdown: str = Field(min_length=1)


class EvaluationCorpus(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    version: Literal[1]
    description: str
    projects: list[CorpusProject] = Field(min_length=1)

    @model_validator(mode="after")
    def _require_unique_slugs(self) -> Self:
        slugs = [project.slug for project in self.projects]
        if len(slugs) != len(set(slugs)):
            raise ValueError("corpus project slugs must be unique")
        return self


class ExpectedSource(BaseModel):
    """A chunk that answers a query, identified by its source page and section heading."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    source_url: str = Field(pattern=r"^/projects/[a-z0-9-]+$")
    heading: str = Field(min_length=1)


class GoldenQuery(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str = Field(pattern=r"^[a-z0-9-]+$")
    question: str = Field(min_length=1, max_length=500)
    kind: QueryKind
    expected: list[ExpectedSource]

    @model_validator(mode="after")
    def _match_expectations_to_kind(self) -> Self:
        if self.kind == "in_scope" and not self.expected:
            raise ValueError(f"in-scope query {self.id!r} needs at least one expected source")
        if self.kind == "out_of_scope" and self.expected:
            raise ValueError(f"out-of-scope query {self.id!r} must not expect a source")
        return self


class GoldenQuerySet(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    version: Literal[1]
    description: str
    queries: list[GoldenQuery] = Field(min_length=1)

    @model_validator(mode="after")
    def _require_unique_ids(self) -> Self:
        ids = [query.id for query in self.queries]
        if len(ids) != len(set(ids)):
            raise ValueError("golden query ids must be unique")
        return self


def load_corpus(path: Path = DEFAULT_CORPUS_PATH) -> EvaluationCorpus:
    return EvaluationCorpus.model_validate_json(path.read_text(encoding="utf-8"))


def load_golden_queries(path: Path = DEFAULT_GOLDEN_QUERIES_PATH) -> GoldenQuerySet:
    return GoldenQuerySet.model_validate_json(path.read_text(encoding="utf-8"))
