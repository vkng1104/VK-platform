#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
check_script="$repository_root/scripts/check-persistence-boundaries.sh"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT INT TERM
fixture_number=0

fail() {
  echo "persistence boundary test failed: $1" >&2
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

new_fixture() {
  fixture_number=$((fixture_number + 1))
  fixture="$test_directory/fixture-$fixture_number"
  mkdir -p "$fixture/apps" "$fixture/config/persistence"
  printf '%s\n' '# rule|path|count|owner|disposition|coverage|reason' \
    >"$fixture/config/persistence/custom-query-registry.txt"
  printf '%s\n' '# service|script|tracked_generated_path' \
    >"$fixture/config/persistence/generators.txt"
}

run_check() {
  PERSISTENCE_REPOSITORY_ROOT="$fixture" "$check_script" 2>&1
}

assert_check_fails() {
  expected="$1"
  if output="$(run_check)"; then
    fail "boundary check unexpectedly passed"
  fi
  assert_contains "$output" "$expected"
}

new_fixture
output="$(run_check)"
assert_contains "$output" "registered custom-query findings=0"

new_fixture
java_path="apps/iam/src/main/java/example/UnsafeRepository.java"
mkdir -p "$fixture/$(dirname "$java_path")"
printf '%s\n' \
  'final class UnsafeRepository {' \
  '  void run(EntityManager entityManager) {' \
  '    entityManager.createNativeQuery("SELECT 1");' \
  '  }' \
  '}' >"$fixture/$java_path"
assert_check_fails "custom-query usage differs from the reviewed registry"

printf '%s\n' \
  "java-native-query|$java_path|1|iam|migrate:orm-phase-2|pending|Replace with typed ORM persistence." \
  >"$fixture/config/persistence/custom-query-registry.txt"
output="$(run_check)"
assert_contains "$output" "registered custom-query findings=1"

printf '%s\n' '  entityManager.createNativeQuery("SELECT 2");' >>"$fixture/$java_path"
assert_check_fails "custom-query usage differs from the reviewed registry"

new_fixture
printf '%s\n' \
  'java-native-query|apps/missing.java|1|iam|migrate:orm-phase-2|pending|' \
  >"$fixture/config/persistence/custom-query-registry.txt"
assert_check_fails "missing reason"

new_fixture
printf '%s\n' \
  'java-native-query|apps/missing.java|1|iam|keep|pending|Invalid disposition.' \
  >"$fixture/config/persistence/custom-query-registry.txt"
assert_check_fails "invalid disposition"

new_fixture
retained_path="apps/iam/src/main/java/example/ComplexRepository.java"
mkdir -p "$fixture/$(dirname "$retained_path")"
printf '%s\n' \
  'final class ComplexRepository {' \
  '  void run(EntityManager entityManager) {' \
  '    entityManager.createNativeQuery("WITH tree AS (SELECT 1) SELECT * FROM tree");' \
  '  }' \
  '}' >"$fixture/$retained_path"
printf '%s\n' \
  "java-native-query|$retained_path|1|iam|retain|pending|A recursive query is clearer in SQL." \
  >"$fixture/config/persistence/custom-query-registry.txt"
assert_check_fails "retained custom query requires coverage"

coverage_path="apps/iam/src/integrationTest/java/example/ComplexRepositoryIntegrationTest.java"
printf '%s\n' \
  "java-native-query|$retained_path|1|iam|retain|$coverage_path|A recursive query is clearer in SQL." \
  >"$fixture/config/persistence/custom-query-registry.txt"
assert_check_fails "coverage path does not exist"

mkdir -p "$fixture/$(dirname "$coverage_path")"
printf '%s\n' 'final class ComplexRepositoryIntegrationTest {}' >"$fixture/$coverage_path"
output="$(run_check)"
assert_contains "$output" "registered custom-query findings=1"

new_fixture
go_path="apps/api/internal/project/repository.go"
mkdir -p "$fixture/$(dirname "$go_path")"
printf '%s\n' \
  'package project' \
  'import "github.com/jackc/pgx/v5"' \
  'const query = `SELECT id FROM projects`' \
  >"$fixture/$go_path"
assert_check_fails "custom-query usage differs from the reviewed registry"

new_fixture
generated_path="apps/api/internal/platform/persistence/ent/client.go"
mkdir -p "$fixture/$(dirname "$generated_path")"
printf '%s\n' \
  'package ent' \
  'import "github.com/jackc/pgx/v5"' \
  'const generatedQuery = `SELECT id FROM generated`' \
  >"$fixture/$generated_path"
printf '%s\n' \
  'api|tools/generate.sh|apps/api/internal/platform/persistence/ent' \
  >"$fixture/config/persistence/generators.txt"
output="$(run_check)"
assert_contains "$output" "registered custom-query findings=0"

new_fixture
mkdir -p "$fixture/apps/api/migrations"
printf '%s\n' 'CREATE TABLE accepted (id UUID PRIMARY KEY);' \
  >"$fixture/apps/api/migrations/000001_accepted.up.sql"
output="$(run_check)"
assert_contains "$output" "registered custom-query findings=0"

mkdir -p "$fixture/apps/api/seed"
printf '%s\n' 'INSERT INTO accepted (id) VALUES (gen_random_uuid());' \
  >"$fixture/apps/api/seed/development.sql"
assert_check_fails "custom-query usage differs from the reviewed registry"

echo "persistence boundary tests passed"
