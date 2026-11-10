#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
lifecycle_script="$repository_root/scripts/ollama-lifecycle.sh"
fake_bin="$repository_root/scripts/test/fixtures/ollama/bin"
test_directory="$(mktemp -d)"
tab="$(printf '\t')"
trap 'rm -rf "$test_directory"' EXIT INT TERM

fail() {
  echo "Ollama lifecycle test failed: $1" >&2
  exit 1
}

assert_contains() {
  value="$1"
  expected="$2"
  case "$value" in
    *"$expected"*) ;;
    *) fail "expected '$expected' in '$value'" ;;
  esac
}

assert_log_excludes() {
  log="$1"
  unexpected="$2"
  if grep -Fq "$unexpected" "$log"; then
    fail "did not expect '$unexpected' in $log"
  fi
}

common_environment() {
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    CALL_LOG="$1" \
    AI_INTERNAL_TOKEN=private-token \
    AI_EMBEDDING_MODEL=embedding-model \
    FAKE_OLLAMA_REACHABLE="${FAKE_OLLAMA_REACHABLE:-true}" \
    FAKE_MODEL_PRESENT="${FAKE_MODEL_PRESENT:-true}" \
    FAKE_MODEL_LOADED="${FAKE_MODEL_LOADED:-false}" \
    FAKE_MODEL_NAME=embedding-model \
    "$lifecycle_script" "$2"
}

compose_log="$test_directory/compose.log"
output="$(
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    CALL_LOG="$compose_log" \
    FAKE_COMPOSE_AI_INTERNAL_TOKEN=private-token \
    FAKE_COMPOSE_AI_EMBEDDING_MODEL=embedding-model \
    "$lifecycle_script" prepare
)"
assert_contains "$output" "already available"
grep -Fq "docker${tab}compose${tab}config${tab}--environment" "$compose_log" ||
  fail "configuration was not resolved through Docker Compose"

missing_config_log="$test_directory/missing-config.log"
if output="$(
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    CALL_LOG="$missing_config_log" \
    "$lifecycle_script" prepare 2>&1
)"; then
  fail "an empty AI_INTERNAL_TOKEN was accepted"
fi
assert_contains "$output" "AI_INTERNAL_TOKEN is required"

existing_log="$test_directory/existing.log"
output="$(common_environment "$existing_log" prepare)"
assert_contains "$output" "already available"
assert_log_excludes "$existing_log" "ollama${tab}pull"

missing_log="$test_directory/missing.log"
output="$(FAKE_MODEL_PRESENT=false common_environment "$missing_log" prepare)"
assert_contains "$output" "Pulling missing Ollama model"
grep -Fq "ollama${tab}pull${tab}embedding-model" "$missing_log" ||
  fail "the missing model was not pulled"

unreachable_log="$test_directory/unreachable.log"
if output="$(
  FAKE_OLLAMA_REACHABLE=false common_environment "$unreachable_log" prepare 2>&1
)"; then
  fail "an unreachable Ollama server was accepted"
fi
assert_contains "$output" "open Ollama.app or run 'ollama serve'"
assert_log_excludes "$unreachable_log" "ollama${tab}show"

loaded_log="$test_directory/loaded.log"
output="$(FAKE_MODEL_LOADED=true common_environment "$loaded_log" stop)"
assert_contains "$output" "unloaded Ollama model embedding-model"
grep -Fq "ollama${tab}stop${tab}embedding-model" "$loaded_log" ||
  fail "the loaded model was not stopped"

idle_log="$test_directory/idle.log"
output="$(common_environment "$idle_log" stop)"
assert_contains "$output" "was not loaded"
assert_log_excludes "$idle_log" "ollama${tab}stop"

stopped_log="$test_directory/stopped.log"
output="$(FAKE_OLLAMA_REACHABLE=false common_environment "$stopped_log" stop)"
assert_contains "$output" "already stopped or unreachable"
assert_log_excludes "$stopped_log" "ollama${tab}ps"

status_log="$test_directory/status.log"
output="$(FAKE_MODEL_LOADED=true common_environment "$status_log" status)"
assert_contains "$output" "Ollama is reachable"
assert_contains "$output" "embedding-model"

echo "Ollama lifecycle tests passed"
