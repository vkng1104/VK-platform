#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
seed_script="$repository_root/scripts/db-seed-local.sh"
fake_bin="$repository_root/scripts/test/fixtures/bin"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT INT TERM

fail() {
  echo "db-seed-local test failed: $1" >&2
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

if output="$(SOURCE_ENV=staging "$seed_script" 2>&1)"; then
  fail "an unsupported source environment was accepted"
fi
assert_contains "$output" "SOURCE_ENV must be either development or production."

if output="$(SOURCE_ENV=development LOCAL_DATABASE_URL=postgres://local "$seed_script" 2>&1)"; then
  fail "an empty development source URL was accepted"
fi
assert_contains "$output" "No database URL is configured for the development environment."

if output="$(
  SOURCE_ENV=production \
    SEED_PRODUCTION_DATABASE_URL=postgres://same \
    LOCAL_DATABASE_URL=postgres://same \
    "$seed_script" 2>&1
)"; then
  fail "matching source and local URLs were accepted"
fi
assert_contains "$output" "The source and local database URLs must be different."

call_log="$test_directory/development-calls.log"
output="$(
  PATH="$fake_bin:$PATH" \
    CALL_LOG="$call_log" \
    SOURCE_ENV=development \
    SEED_DEVELOPMENT_DATABASE_URL='postgres://development.example/vk' \
    SEED_PRODUCTION_DATABASE_URL='postgres://production.example/vk' \
    LOCAL_DATABASE_URL='postgres://local.example/vk' \
    "$seed_script"
)"
assert_contains "$output" "Local database seeded from development."

first_call="$(sed -n '1p' "$call_log")"
second_call="$(sed -n '2p' "$call_log")"
third_call="$(sed -n '3p' "$call_log")"
assert_contains "$first_call" "pg_dump"
assert_contains "$first_call" "--dbname=postgres://development.example/vk"
assert_contains "$first_call" "--exclude-table-data=public.email_verification_challenges"
assert_contains "$second_call" "psql"
assert_contains "$second_call" "postgres://local.example/vk"
assert_contains "$third_call" "pg_restore"
assert_contains "$third_call" "--dbname=postgres://local.example/vk"

call_log="$test_directory/production-calls.log"
output="$(
  PATH="$fake_bin:$PATH" \
    CALL_LOG="$call_log" \
    SOURCE_ENV=production \
    SEED_DEVELOPMENT_DATABASE_URL='postgres://development.example/vk' \
    SEED_PRODUCTION_DATABASE_URL='postgres://production.example/vk' \
    LOCAL_DATABASE_URL='postgres://local.example/vk' \
    "$seed_script"
)"
assert_contains "$output" "Local database seeded from production."
assert_contains "$(sed -n '1p' "$call_log")" "--dbname=postgres://production.example/vk"

echo "db-seed-local tests passed"
