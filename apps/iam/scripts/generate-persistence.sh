#!/bin/sh

set -eu

iam_root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

cd "$iam_root"
./gradlew clean generatePersistence --no-daemon
