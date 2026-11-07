FROM ghcr.io/astral-sh/uv:0.9.18 AS uv

FROM python:3.13-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app
COPY --from=uv /uv /uvx /bin/
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"

FROM base AS development

RUN uv sync --frozen --group dev
CMD ["uvicorn", "vk_ai.main:create_default_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]

FROM development AS lint

RUN ruff check src && ruff format --check src && mypy

FROM development AS test

COPY tests ./tests
RUN pytest

FROM base AS runtime

RUN addgroup --system ai && adduser --system --ingroup ai ai
USER ai
EXPOSE 8090
CMD ["uvicorn", "vk_ai.main:create_default_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]
