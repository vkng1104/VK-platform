from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlparse


class ConfigurationError(ValueError):
    """Raised when required runtime configuration is invalid."""


@dataclass(frozen=True, slots=True)
class Settings:
    internal_token: str
    resource_api_base_url: str
    embedding_base_url: str
    embedding_model: str
    embedding_batch_size: int = 16
    chunk_max_chars: int = 1_200
    chunk_overlap_chars: int = 120
    retrieval_min_score: float = 0.0
    upstream_timeout_seconds: float = 10.0
    maximum_response_bytes: int = 4 * 1024 * 1024

    @classmethod
    def from_env(cls) -> Settings:
        provider = os.getenv("AI_EMBEDDING_PROVIDER", "ollama").strip()
        if provider != "ollama":
            raise ConfigurationError("AI_EMBEDDING_PROVIDER must be ollama")

        settings = cls(
            internal_token=_required("AI_INTERNAL_TOKEN"),
            resource_api_base_url=_url("AI_RESOURCE_API_BASE_URL", "http://api:8080"),
            embedding_base_url=_url("AI_EMBEDDING_BASE_URL", "http://host.docker.internal:11434"),
            embedding_model=_required("AI_EMBEDDING_MODEL"),
            embedding_batch_size=_positive_int("AI_EMBEDDING_BATCH_SIZE", 16),
            chunk_max_chars=_positive_int("AI_CHUNK_MAX_CHARS", 1_200),
            chunk_overlap_chars=_non_negative_int("AI_CHUNK_OVERLAP_CHARS", 120),
            retrieval_min_score=_bounded_float("AI_RETRIEVAL_MIN_SCORE", 0.0, -1.0, 1.0),
            upstream_timeout_seconds=_positive_float("AI_UPSTREAM_TIMEOUT_SECONDS", 10.0),
            maximum_response_bytes=_positive_int("AI_MAXIMUM_RESPONSE_BYTES", 4 * 1024 * 1024),
        )
        if settings.chunk_overlap_chars >= settings.chunk_max_chars:
            raise ConfigurationError(
                "AI_CHUNK_OVERLAP_CHARS must be smaller than AI_CHUNK_MAX_CHARS"
            )
        return settings


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ConfigurationError(f"{name} is required")
    return value


def _url(name: str, default: str) -> str:
    value = os.getenv(name, default).strip().rstrip("/")
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ConfigurationError(f"{name} must be an absolute HTTP URL")
    return value


def _positive_int(name: str, default: int) -> int:
    value = _integer(name, default)
    if value <= 0:
        raise ConfigurationError(f"{name} must be greater than zero")
    return value


def _non_negative_int(name: str, default: int) -> int:
    value = _integer(name, default)
    if value < 0:
        raise ConfigurationError(f"{name} must not be negative")
    return value


def _integer(name: str, default: int) -> int:
    raw = os.getenv(name, str(default)).strip()
    try:
        return int(raw)
    except ValueError as error:
        raise ConfigurationError(f"{name} must be an integer") from error


def _positive_float(name: str, default: float) -> float:
    value = _float(name, default)
    if value <= 0:
        raise ConfigurationError(f"{name} must be greater than zero")
    return value


def _bounded_float(name: str, default: float, minimum: float, maximum: float) -> float:
    value = _float(name, default)
    if value < minimum or value > maximum:
        raise ConfigurationError(f"{name} must be between {minimum} and {maximum}")
    return value


def _float(name: str, default: float) -> float:
    raw = os.getenv(name, str(default)).strip()
    try:
        return float(raw)
    except ValueError as error:
        raise ConfigurationError(f"{name} must be a number") from error
