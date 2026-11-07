import pytest

from vk_ai.config import ConfigurationError, Settings


def test_settings_require_private_token_and_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AI_INTERNAL_TOKEN", raising=False)
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "embedding-model")

    with pytest.raises(ConfigurationError, match="AI_INTERNAL_TOKEN"):
        Settings.from_env()


def test_settings_reject_overlap_that_covers_a_chunk(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_INTERNAL_TOKEN", "private-token")
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "embedding-model")
    monkeypatch.setenv("AI_CHUNK_MAX_CHARS", "100")
    monkeypatch.setenv("AI_CHUNK_OVERLAP_CHARS", "100")

    with pytest.raises(ConfigurationError, match="AI_CHUNK_OVERLAP_CHARS"):
        Settings.from_env()
