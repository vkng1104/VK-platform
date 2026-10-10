#!/bin/sh

set -eu

repository_root="${PERSISTENCE_REPOSITORY_ROOT:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}"
generators_path="${PERSISTENCE_GENERATORS_PATH:-$repository_root/config/persistence/generators.txt}"

fail() {
  echo "persistence generation failed: $1" >&2
  exit 1
}

is_safe_relative_path() {
  candidate="$1"
  case "$candidate" in
    ""|/*|*".."*) return 1 ;;
    *) return 0 ;;
  esac
}

[ -f "$generators_path" ] || fail "missing generator manifest at $generators_path"

generator_count=0
while IFS='|' read -r service script generated_path extra; do
  case "$service" in ""|'#'*) continue ;; esac
  [ -z "${extra:-}" ] || fail "invalid generator entry for $service"
  [ -n "$service" ] || fail "generator service name is required"
  is_safe_relative_path "$script" || fail "unsafe generator script path: $script"
  if [ "$generated_path" != "-" ]; then
    is_safe_relative_path "$generated_path" || fail "unsafe generated path: $generated_path"
  fi
  [ -x "$repository_root/$script" ] || fail "generator is not executable: $script"
  echo "Generating persistence sources for $service..."
  "$repository_root/$script"
  generator_count=$((generator_count + 1))
done <"$generators_path"

if [ "$generator_count" -eq 0 ]; then
  echo "No persistence generators are registered yet."
else
  echo "Persistence generation completed; generators=$generator_count"
fi
