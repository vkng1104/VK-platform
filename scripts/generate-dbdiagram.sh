#!/bin/sh

set -eu

script_directory="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
repository_root="${DBDIAGRAM_REPOSITORY_ROOT:-$(CDPATH= cd -- "$script_directory/.." && pwd)}"
manifest_path="${DBDIAGRAM_MANIFEST_PATH:-$repository_root/config/dbdiagram/services.txt}"
output_root="${DBDIAGRAM_OUTPUT_ROOT:-$repository_root}"
render_query="$script_directory/dbdiagram/render.sql"
postgres_image="postgres:17-alpine"
container_name="vk-platform-dbdiagram-$$"
container_started=false
temporary_directory="$(mktemp -d)"
services_path="$temporary_directory/services.txt"

cleanup() {
  if [ "$container_started" = true ]; then
    docker rm --force "$container_name" >/dev/null 2>&1 || true
  fi
  rm -rf "$temporary_directory"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

fail() {
  echo "dbdiagram generation failed: $1" >&2
  exit 1
}

is_safe_relative_path() {
  case "$1" in
    ''|/*|..|../*|*/..|*/../*) return 1 ;;
  esac
}

run_sql() {
  docker exec "$container_name" psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 \
    --host 127.0.0.1 --username postgres "$@" </dev/null
}

run_sql_file() {
  docker exec --interactive "$container_name" psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 \
    --host 127.0.0.1 --username postgres --file - "$@"
}

# Writes the service's migration paths to $3, one per line, in the order the
# migration tool applies them.
list_migrations() {
  tool="$1"
  directory="$2"
  list_path="$3"
  : >"$list_path.unsorted"

  case "$tool" in
    golang-migrate) pattern='*.up.sql' ;;
    flyway) pattern='*.sql' ;;
    *) fail "unknown migration tool '$tool'" ;;
  esac

  for path in "$directory"/$pattern; do
    [ -f "$path" ] || continue
    name="${path##*/}"
    version=
    case "$tool:$name" in
      golang-migrate:*) version="${name%%_*}" ;;
      flyway:V*__*.sql)
        version="${name#V}"
        version="${version%%__*}"
        ;;
    esac
    case "$version" in
      ''|*[!0-9]*) fail "cannot order $tool migration $name" ;;
    esac
    printf '%s %s\n' "$version" "$path" >>"$list_path.unsorted"
  done

  duplicate="$(cut -d ' ' -f 1 "$list_path.unsorted" | sort -n | uniq -d | head -n 1)"
  [ -z "$duplicate" ] || fail "duplicate $tool migration version $duplicate in $directory"
  sort -n -k 1,1 "$list_path.unsorted" | cut -d ' ' -f 2- >"$list_path"
  [ -s "$list_path" ] || fail "no $tool migrations found in $directory"
}

[ -f "$manifest_path" ] || fail "missing service manifest at $manifest_path"
[ -f "$render_query" ] || fail "missing render query at $render_query"
: >"$services_path"

while IFS='|' read -r service tool migrations_directory output_directory extra; do
  case "$service" in ""|'#'*) continue ;; esac
  [ -z "${extra:-}" ] || fail "invalid manifest entry for $service"
  case "$service" in *[!a-z0-9_]*) fail "invalid service name '$service'" ;; esac
  is_safe_relative_path "$migrations_directory" || fail "unsafe migrations path for $service"
  is_safe_relative_path "$output_directory" || fail "unsafe output path for $service"
  [ -d "$repository_root/$migrations_directory" ] ||
    fail "missing migrations directory for $service: $migrations_directory"
  list_migrations "$tool" "$repository_root/$migrations_directory" "$temporary_directory/$service.migrations"
  printf '%s|%s|%s\n' "$service" "$migrations_directory" "$output_directory" >>"$services_path"
done <"$manifest_path"

[ -s "$services_path" ] || fail "no services are registered in $manifest_path"
command -v docker >/dev/null 2>&1 || fail "docker is required to replay migrations"

docker run --detach --rm --name "$container_name" \
  --env POSTGRES_HOST_AUTH_METHOD=trust \
  --tmpfs /var/lib/postgresql/data \
  "$postgres_image" >/dev/null
container_started=true

# The image's init server only listens on its Unix socket, so a TCP check
# waits for the final server.
attempt=0
until docker exec "$container_name" pg_isready --quiet --host 127.0.0.1 --username postgres; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 60 ] || fail "PostgreSQL did not become ready"
  sleep 1
done

while IFS='|' read -r service migrations_directory output_directory; do
  run_sql --dbname postgres --command "CREATE DATABASE $service" >/dev/null

  while IFS= read -r migration; do
    run_sql_file --dbname "$service" --single-transaction <"$migration" >/dev/null ||
      fail "could not apply ${migration#"$repository_root"/}"
  done <"$temporary_directory/$service.migrations"

  schemas="$(run_sql --dbname "$service" --tuples-only --no-align --command "
    SELECT n.nspname
    FROM pg_namespace AS n
    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
      AND EXISTS (
        SELECT 1 FROM pg_class AS c WHERE c.relnamespace = n.oid AND c.relkind IN ('r', 'p')
      )
    ORDER BY n.nspname")"

  staging_directory="$temporary_directory/output/$service"
  mkdir -p "$staging_directory"
  diagram_count=0
  for schema in $schemas; do
    case "$schema" in *[!a-z0-9_]*) fail "cannot name a diagram for schema '$schema'" ;; esac
    case "$schema" in
      "$service"_*) diagram_name="$schema" ;;
      *) diagram_name="${service}_$schema" ;;
    esac
    {
      printf '%s\n' \
        '// GENERATED FILE - DO NOT EDIT.' \
        "// Source of truth: $migrations_directory" \
        '// Regenerate with: make dbdiagram' \
        ''
      run_sql_file --dbname "$service" --tuples-only --no-align \
        --set schema="$schema" --set service="$service" --set project="$diagram_name" <"$render_query"
    } >"$staging_directory/$diagram_name.dbml" || fail "could not render schema $schema of $service"
    diagram_count=$((diagram_count + 1))
  done

  target_directory="$output_root/$output_directory"
  mkdir -p "$target_directory"
  find "$target_directory" -maxdepth 1 -type f -name '*.dbml' -exec rm -f {} +
  if [ "$diagram_count" -gt 0 ]; then
    cp "$staging_directory"/*.dbml "$target_directory"/
  fi
  echo "wrote $diagram_count diagram(s) for $service to $output_directory"
done <"$services_path"
