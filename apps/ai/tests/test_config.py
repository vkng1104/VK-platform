import pytest

from vk_ai.config import ConfigurationError, Settings


def test_settings_require_private_token_and_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AI_INTERNAL_TOKEN", raising=False)
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "embedding-model")

    with pytest.raises(ConfigurationError, match="AI_INTERNAL_TOKEN"):
        Settings.from_env()


def _required_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_INTERNAL_TOKEN", "private-token")
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "embedding-model")
    for name in ("AI_EMBEDDING_INPUT_FORMAT", "AI_RETRIEVAL_MIN_SCORE"):
        monkeypatch.delenv(name, raising=False)


def test_settings_default_to_automatic_input_format_and_measured_threshold(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _required_env(monkeypatch)

    settings = Settings.from_env()

    assert settings.embedding_input_format == "auto"
    assert settings.retrieval_min_score == 0.26


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("embeddinggemma", "embeddinggemma"), (" EmbeddingGemma ", "embeddinggemma"), ("raw", "raw")],
)
def test_settings_accept_explicit_input_formats(
    monkeypatch: pytest.MonkeyPatch, raw: str, expected: str
) -> None:
    _required_env(monkeypatch)
    monkeypatch.setenv("AI_EMBEDDING_INPUT_FORMAT", raw)

    assert Settings.from_env().embedding_input_format == expected


def test_settings_reject_unknown_input_format(monkeypatch: pytest.MonkeyPatch) -> None:
    _required_env(monkeypatch)
    monkeypatch.setenv("AI_EMBEDDING_INPUT_FORMAT", "nomic")

    with pytest.raises(ConfigurationError, match="AI_EMBEDDING_INPUT_FORMAT"):
        Settings.from_env()


def test_settings_reject_overlap_that_covers_a_chunk(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_INTERNAL_TOKEN", "private-token")
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "embedding-model")
    monkeypatch.setenv("AI_CHUNK_MAX_CHARS", "100")
    monkeypatch.setenv("AI_CHUNK_OVERLAP_CHARS", "100")

    with pytest.raises(ConfigurationError, match="AI_CHUNK_OVERLAP_CHARS"):
        Settings.from_env()
