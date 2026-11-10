from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import TextIO

_APPLICATION_LOGGER_NAME = "vk_ai"
_HANDLER_NAME = "vk_ai_json_stdout"
_STANDARD_LOG_RECORD_FIELDS = frozenset(logging.makeLogRecord({}).__dict__) | {
    "asctime",
    "message",
}


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": datetime.fromtimestamp(record.created, UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "service": "ai",
        }
        for key, value in record.__dict__.items():
            if key not in _STANDARD_LOG_RECORD_FIELDS and key not in payload:
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        if record.stack_info:
            payload["stack"] = self.formatStack(record.stack_info)
        return json.dumps(payload, default=str, separators=(",", ":"), sort_keys=True)


def configure_logging(*, stream: TextIO | None = None) -> None:
    application_logger = logging.getLogger(_APPLICATION_LOGGER_NAME)
    application_logger.setLevel(logging.INFO)
    application_logger.propagate = False
    if any(handler.name == _HANDLER_NAME for handler in application_logger.handlers):
        return

    handler = logging.StreamHandler(stream if stream is not None else sys.stdout)
    handler.set_name(_HANDLER_NAME)
    handler.setLevel(logging.INFO)
    handler.setFormatter(JsonLogFormatter())
    application_logger.addHandler(handler)
