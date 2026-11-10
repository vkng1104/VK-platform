import json
import logging
from collections.abc import Iterator
from io import StringIO

import pytest

import vk_ai.main as main
from vk_ai.logging_config import JsonLogFormatter, configure_logging


@pytest.fixture
def isolated_application_logger() -> Iterator[tuple[logging.Logger, StringIO]]:
    logger = logging.getLogger("vk_ai")
    original_handlers = logger.handlers.copy()
    original_level = logger.level
    original_propagate = logger.propagate
    stream = StringIO()
    logger.handlers.clear()
    try:
        yield logger, stream
    finally:
        for handler in logger.handlers:
            handler.close()
        logger.handlers[:] = original_handlers
        logger.setLevel(original_level)
        logger.propagate = original_propagate


def test_json_log_formatter_writes_extra_fields() -> None:
    record = logging.makeLogRecord(
        {
            "name": "vk_ai.retrieval.service",
            "levelno": logging.INFO,
            "levelname": "INFO",
            "msg": "retrieval index ready",
            "args": (),
            "document_count": 2,
            "chunk_count": 7,
            "build_duration_ms": 12.5,
        }
    )

    payload = json.loads(JsonLogFormatter().format(record))

    assert payload["level"] == "INFO"
    assert payload["logger"] == "vk_ai.retrieval.service"
    assert payload["message"] == "retrieval index ready"
    assert payload["service"] == "ai"
    assert payload["document_count"] == 2
    assert payload["chunk_count"] == 7
    assert payload["build_duration_ms"] == 12.5
    assert payload["timestamp"].endswith("Z")


def test_configure_logging_enables_info_once(
    isolated_application_logger: tuple[logging.Logger, StringIO],
) -> None:
    logger, stream = isolated_application_logger

    configure_logging(stream=stream)
    configure_logging(stream=stream)
    logging.getLogger("vk_ai.retrieval.service").info(
        "retrieval completed", extra={"result_count": 3}
    )

    assert logger.isEnabledFor(logging.INFO)
    assert len(logger.handlers) == 1
    assert json.loads(stream.getvalue())["result_count"] == 3


def test_create_default_app_configures_application_logging(
    isolated_application_logger: tuple[logging.Logger, StringIO],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    logger, stream = isolated_application_logger

    class FakeAsyncClient:
        def __init__(self, **_: object) -> None:
            pass

        async def aclose(self) -> None:
            pass

    monkeypatch.setenv("AI_INTERNAL_TOKEN", "private-token")
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "embeddinggemma:300m")
    monkeypatch.setattr(main.httpx, "AsyncClient", FakeAsyncClient)
    monkeypatch.setattr(main, "configure_logging", lambda: configure_logging(stream=stream))

    main.create_default_app()

    assert logger.isEnabledFor(logging.INFO)
    assert len(logger.handlers) == 1
