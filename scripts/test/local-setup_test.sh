#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
cd "$repository_root"

fail() {
  echo "local setup test failed: $1" >&2
  exit 1
}

docker compose config --quiet
make -n dev >/dev/null
make -n dev-reset >/dev/null
make -n db-seed-local SOURCE_ENV=development >/dev/null

monitor_command="$(make -n dev-monitor)"
case "$monitor_command" in
  *"docker compose logs --follow api"*) ;;
  *) fail "dev-monitor does not follow backend logs" ;;
esac

grep -Fq 'npm run dev --workspace @vk-platform/web -- --hostname 0.0.0.0' docker-compose.yml ||
  fail "the Compose web service does not forward Next.js arguments correctly"
if grep -Fq 'npm run dev:web -- --hostname' docker-compose.yml; then
  fail "the Compose web service still uses the broken nested npm argument forwarding"
fi

if git ls-files --error-unmatch go.work >/dev/null 2>&1; then
  fail "go.work is still tracked"
fi
git check-ignore --quiet go.work || fail "go.work is not ignored"
git check-ignore --quiet go.work.sum || fail "go.work.sum is not ignored"

grep -q '^  pull_request:' .github/workflows/tests.yml || fail "PR tests trigger is missing"
if grep -q '^  push:' .github/workflows/tests.yml; then
  fail "PR tests must not run after pushes to master"
fi
grep -q '^  pull_request:' .github/workflows/lint.yml || fail "PR lint trigger is missing"
grep -q '^  push:' .github/workflows/lint.yml || fail "master lint trigger is missing"

"$repository_root/scripts/test/db-seed-local_test.sh"

echo "local setup tests passed"
