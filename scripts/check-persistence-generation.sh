#!/bin/sh

set -eu

repository_root="${PERSISTENCE_REPOSITORY_ROOT:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}"
generators_path="${PERSISTENCE_GENERATORS_PATH:-$repository_root/config/persistence/generators.txt}"
generator_script="${PERSISTENCE_GENERATOR_SCRIPT:-$repository_root/scripts/generate-persistence.sh}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT INT TERM

paths_path="$temporary_directory/generated-paths.txt"
: >"$paths_path"

fail() {
  echo "persistence generation check failed: $1" >&2
  exit 1
}

[ -f "$generators_path" ] || fail "missing generator manifest at $generators_path"
[ -x "$generator_script" ] || fail "generator script is not executable: $generator_script"

while IFS='|' read -r service script generated_path extra; do
  case "$service" in ""|'#'*) continue ;; esac
  [ -z "${extra:-}" ] || fail "invalid generator entry for $service"
  [ "$generated_path" != "-" ] || continue
  printf '%s\n' "$generated_path" >>"$paths_path"
done <"$generators_path"

if [ -s "$paths_path" ]; then
  while IFS= read -r generated_path; do
    if [ -n "$(git -C "$repository_root" status --porcelain -- "$generated_path")" ]; then
      fail "generated path must be clean before checking: $generated_path"
    fi
  done <"$paths_path"
fi

PERSISTENCE_REPOSITORY_ROOT="$repository_root" \
PERSISTENCE_GENERATORS_PATH="$generators_path" \
  "$generator_script"

if [ -s "$paths_path" ]; then
  while IFS= read -r generated_path; do
    if [ -n "$(git -C "$repository_root" status --porcelain -- "$generated_path")" ]; then
      git -C "$repository_root" status --short -- "$generated_path" >&2
      fail "persistence generation drift detected in $generated_path"
    fi
  done <"$paths_path"
fi

echo "Persistence generation is reproducible."
