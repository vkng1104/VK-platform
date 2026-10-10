#!/bin/sh

set -eu

api_root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

cd "$api_root"
go generate ./internal/platform/persistence/ent
