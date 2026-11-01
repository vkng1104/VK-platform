#!/bin/sh

set -eu

# Maps changed repository paths, one per line on stdin, to the CI areas whose
# jobs must run, printed as `<area>=true|false` lines for $GITHUB_OUTPUT.
# Shared build files, paths this table does not recognize, and an empty list
# switch every area on, so an unknown change can never skip a check.

web=false
api=false
iam=false
persistence=false
setup=false
changed=false

enable_all() {
  web=true
  api=true
  iam=true
  persistence=true
  setup=true
}

while IFS= read -r path || [ -n "$path" ]; do
  [ -n "$path" ] || continue
  changed=true

  case "$path" in
    Makefile|docker-compose.yml|.github/workflows/*)
      enable_all
      ;;
    DESIGN.md|.cursor/*|.github/pull_request_template.md|apps/api/docs/*|apps/iam/docs/*|apps/web/docs/*)
      ;;
    AGENTS.md|README.md|.agents/*|.env.example)
      setup=true
      ;;
    apps/web/*|internal/content/*|package.json|package-lock.json|.nvmrc)
      web=true
      ;;
    apps/api/*)
      api=true
      persistence=true
      ;;
    apps/iam/*)
      iam=true
      persistence=true
      ;;
    config/persistence/*|scripts/*persistence*)
      persistence=true
      setup=true
      ;;
    config/*|scripts/*)
      setup=true
      ;;
    *.md)
      ;;
    *)
      enable_all
      ;;
  esac
done

[ "$changed" = true ] || enable_all

printf 'web=%s\napi=%s\niam=%s\npersistence=%s\nsetup=%s\n' \
  "$web" "$api" "$iam" "$persistence" "$setup"
