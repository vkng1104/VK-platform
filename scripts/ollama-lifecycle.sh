#!/bin/sh

set -eu

ollama_url="${OLLAMA_HOST_URL:-http://127.0.0.1:11434}"

fail() {
  echo "Ollama setup failed: $1" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

compose_environment_value() {
  name="$1"
  require_command docker
  docker compose config --environment 2>/dev/null |
    awk -F= -v name="$name" '
      $1 == name {
        sub(/^[^=]*=/, "")
        value = $0
      }
      END { print value }
    '
}

configured_internal_token() {
  if [ -n "${AI_INTERNAL_TOKEN:-}" ]; then
    printf '%s\n' "$AI_INTERNAL_TOKEN"
    return
  fi
  compose_environment_value AI_INTERNAL_TOKEN
}

configured_model() {
  if [ -n "${AI_EMBEDDING_MODEL:-}" ]; then
    printf '%s\n' "$AI_EMBEDDING_MODEL"
    return
  fi
  compose_environment_value AI_EMBEDDING_MODEL
}

ollama_is_reachable() {
  curl --fail --silent --show-error --max-time 3 "$ollama_url/api/tags" >/dev/null 2>&1
}

require_runtime() {
  require_command curl
  require_command ollama
  if ! ollama_is_reachable; then
    fail "Ollama is not reachable at $ollama_url; open Ollama.app or run 'ollama serve'"
  fi
}

prepare() {
  internal_token="$(configured_internal_token)"
  [ -n "$internal_token" ] || fail "AI_INTERNAL_TOKEN is required in the shell or .env"

  model="$(configured_model)"
  [ -n "$model" ] || fail "AI_EMBEDDING_MODEL is required in the shell or .env"

  require_runtime
  if ollama show "$model" >/dev/null 2>&1; then
    echo "Ollama model $model is already available."
    return
  fi

  echo "Pulling missing Ollama model $model..."
  ollama pull "$model"
}

status() {
  require_command curl
  if ! ollama_is_reachable; then
    echo "Ollama is not reachable at $ollama_url."
    return
  fi

  require_command ollama
  echo "Ollama is reachable at $ollama_url."
  ollama ps
}

stop() {
  require_command curl
  if ! ollama_is_reachable; then
    echo "Ollama is already stopped or unreachable at $ollama_url."
    return
  fi

  require_command ollama
  model="$(configured_model)"
  [ -n "$model" ] || fail "AI_EMBEDDING_MODEL is required in the shell or .env"

  running_models="$(ollama ps)" || fail "could not list running Ollama models"
  if printf '%s\n' "$running_models" |
    awk -v model="$model" 'NR > 1 && $1 == model { found = 1 } END { exit !found }'; then
    ollama stop "$model"
    echo "Stopped AI and unloaded Ollama model $model."
    return
  fi

  echo "AI is stopped; Ollama model $model was not loaded."
}

case "${1:-}" in
  prepare)
    prepare
    ;;
  status)
    status
    ;;
  stop)
    stop
    ;;
  *)
    echo "Usage: $0 prepare|status|stop" >&2
    exit 2
    ;;
esac
