#!/bin/sh

set -eu

script_directory="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
repository_root="${DBDIAGRAM_REPOSITORY_ROOT:-$(CDPATH= cd -- "$script_directory/.." && pwd)}"
manifest_path="${DBDIAGRAM_MANIFEST_PATH:-$repository_root/config/dbdiagram/services.txt}"
generator_script="$script_directory/generate-dbdiagram.sh"
temporary_directory="$(mktemp -d)"
generated_root="$temporary_directory/generated"
trap 'rm -rf "$temporary_directory"' EXIT INT TERM

fail() {
  echo "dbdiagram check failed: $1" >&2
  exit 1
}

DBDIAGRAM_REPOSITORY_ROOT="$repository_root" \
DBDIAGRAM_MANIFEST_PATH="$manifest_path" \
DBDIAGRAM_OUTPUT_ROOT="$generated_root" \
  "$generator_script" >/dev/null

stale=false
while IFS='|' read -r service tool migrations_directory output_directory extra; do
  case "$service" in ""|'#'*) continue ;; esac
  if [ ! -d "$repository_root/$output_directory" ]; then
    echo "missing committed diagrams for $service: $output_directory" >&2
    stale=true
  elif ! (cd "$repository_root" && diff -ru "$output_directory" "$generated_root/$output_directory") >&2; then
    stale=true
  fi
done <"$manifest_path"

[ "$stale" = false ] ||
  fail "the diagrams above do not match their migrations; run \`make dbdiagram\` and commit the result"

echo "dbdiagram files match their migrations"
