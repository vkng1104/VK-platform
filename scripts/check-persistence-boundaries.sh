#!/bin/sh

set -eu

repository_root="${PERSISTENCE_REPOSITORY_ROOT:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}"
registry_path="${PERSISTENCE_QUERY_REGISTRY_PATH:-$repository_root/config/persistence/custom-query-registry.txt}"
generators_path="${PERSISTENCE_GENERATORS_PATH:-$repository_root/config/persistence/generators.txt}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT INT TERM

actual_path="$temporary_directory/actual.txt"
expected_path="$temporary_directory/expected.txt"
: >"$actual_path"
: >"$expected_path"

fail() {
  echo "persistence boundary check failed: $1" >&2
  exit 1
}

is_safe_relative_path() {
  candidate="$1"
  case "$candidate" in
    ""|/*|*".."*) return 1 ;;
    *) return 0 ;;
  esac
}

is_generated_path() {
  candidate="$1"
  [ -f "$generators_path" ] || return 1

  while IFS='|' read -r service script generated_path extra; do
    case "$service" in ""|'#'*) continue ;; esac
    [ -z "${extra:-}" ] || fail "invalid generator entry for $service"
    [ "$generated_path" != "-" ] || continue
    case "$candidate" in
      "$generated_path"|"$generated_path"/*) return 0 ;;
    esac
  done <"$generators_path"

  return 1
}

record_matches() {
  rule="$1"
  relative_path="$2"
  expression="$3"
  count="$(LC_ALL=C grep -E -c "$expression" "$repository_root/$relative_path" || true)"
  if [ "$count" -gt 0 ]; then
    printf '%s|%s|%s\n' "$rule" "$relative_path" "$count" >>"$actual_path"
  fi
}

[ -f "$registry_path" ] || fail "missing custom-query registry at $registry_path"
[ -f "$generators_path" ] || fail "missing generator manifest at $generators_path"

while IFS='|' read -r rule path count owner disposition coverage reason extra; do
  case "$rule" in ""|'#'*) continue ;; esac
  [ -z "${extra:-}" ] || fail "invalid custom-query entry for $path"
  is_safe_relative_path "$path" || fail "unsafe custom-query path: $path"
  case "$count" in ''|*[!0-9]*) fail "invalid custom-query count for $path" ;; esac
  [ "$count" -gt 0 ] || fail "custom-query count must be positive for $path"
  [ -n "$owner" ] || fail "missing owner for $path"
  case "$disposition" in
    retain) ;;
    migrate:*) [ -n "${disposition#migrate:}" ] || fail "missing migration phase for $path" ;;
    *) fail "invalid disposition for $path" ;;
  esac
  case "$coverage" in
    pending)
      [ "$disposition" != "retain" ] || fail "retained custom query requires coverage for $path"
      ;;
    *)
      is_safe_relative_path "$coverage" || fail "unsafe coverage path for $path"
      [ -f "$repository_root/$coverage" ] || fail "coverage path does not exist for $path: $coverage"
      ;;
  esac
  [ -n "$reason" ] || fail "missing reason for $path"
  [ -f "$repository_root/$path" ] || fail "registered custom-query path does not exist: $path"
  printf '%s|%s|%s\n' "$rule" "$path" "$count" >>"$expected_path"
done <"$registry_path"

find "$repository_root/apps" -type f -name '*.java' \
  ! -path '*/src/test/*' \
  ! -path '*/src/integrationTest/*' \
  ! -path '*/build/*' \
  | LC_ALL=C sort \
  | while IFS= read -r file; do
      relative_path="${file#"$repository_root/"}"
      is_generated_path "$relative_path" && continue
      record_matches 'java-native-query' "$relative_path" 'createNativeQuery[[:space:]]*\(|@NativeQuery([[:space:]]|\()|nativeQuery[[:space:]]*=[[:space:]]*true'
      record_matches 'java-string-query' "$relative_path" 'createQuery[[:space:]]*\(|@Query[[:space:]]*\('
      record_matches 'java-jdbc-template' "$relative_path" 'JdbcTemplate|NamedParameterJdbcTemplate'
    done

find "$repository_root/apps" -type f -name '*.go' \
  ! -name '*_test.go' \
  ! -path '*/vendor/*' \
  | LC_ALL=C sort \
  | while IFS= read -r file; do
      relative_path="${file#"$repository_root/"}"
      is_generated_path "$relative_path" && continue
      case "$relative_path" in
        apps/api/internal/platform/database/*|apps/api/cmd/migrate/*) ;;
        *) record_matches 'go-direct-pgx' "$relative_path" '"github\.com/jackc/pgx/v5([^\"]*)"' ;;
      esac
      record_matches 'go-raw-sql-text' "$relative_path" '(^|[^[:alnum:]_])(SELECT([[:space:]]|$)|INSERT[[:space:]]+INTO|UPDATE[[:space:]]+[a-zA-Z_]|DELETE[[:space:]]+FROM|WITH[[:space:]]+[a-zA-Z_])'
    done

find "$repository_root/apps" -type f -name '*.sql' \
  ! -path '*/migrations/*' \
  ! -path '*/src/main/resources/db/migration/*' \
  ! -path '*/test/*' \
  ! -path '*/build/*' \
  | LC_ALL=C sort \
  | while IFS= read -r file; do
      relative_path="${file#"$repository_root/"}"
      is_generated_path "$relative_path" && continue
      printf 'application-sql-file|%s|1\n' "$relative_path" >>"$actual_path"
    done

LC_ALL=C sort -o "$actual_path" "$actual_path"
LC_ALL=C sort -o "$expected_path" "$expected_path"

if ! diff -u "$expected_path" "$actual_path"; then
  fail "custom-query usage differs from the reviewed registry"
fi

finding_count="$(wc -l <"$actual_path" | tr -d ' ')"
echo "Persistence boundaries passed; registered custom-query findings=$finding_count"
