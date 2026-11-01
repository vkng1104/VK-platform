#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
changed_areas_script="$repository_root/scripts/ci/changed-areas.sh"
all_areas='web api iam persistence setup'

fail() {
  echo "changed areas test failed: $1" >&2
  exit 1
}

enabled_areas() {
  printf '%s\n' "$1" | "$changed_areas_script" | sed -n 's/=true$//p' | tr '\n' ' ' | sed 's/ $//'
}

assert_areas() {
  description="$1"
  paths="$2"
  expected="$3"
  actual="$(enabled_areas "$paths")"
  [ "$actual" = "$expected" ] || fail "$description: expected '$expected', got '$actual'"
}

assert_areas 'skill changes run only the setup checks that read them' \
  '.agents/skills/feature-delivery/SKILL.md
.agents/skills/write-project-plan/SKILL.md' \
  'setup'
assert_areas 'design notes, app docs, and editor rules run nothing' \
  'DESIGN.md
apps/iam/docs/passwordless-identity-engineering-note.md
.cursor/rules/testing.mdc
.github/pull_request_template.md' \
  ''
assert_areas 'the setup test reads README.md' 'README.md' 'setup'
assert_areas 'the policy test reads AGENTS.md' 'AGENTS.md' 'setup'
assert_areas 'web source runs the frontend jobs' 'apps/web/src/app/page.tsx' 'web'
assert_areas 'root npm files run the frontend jobs' 'package-lock.json' 'web'
assert_areas 'public content feeds the frontend' 'internal/content/cv/public/profile.json' 'web'
assert_areas 'API code runs API and persistence jobs' 'apps/api/internal/projects/service.go' 'api persistence'
assert_areas 'IAM code runs IAM and persistence jobs' \
  'apps/iam/src/main/java/com/vkplatform/iam/session/domain/AuthenticationSession.java' \
  'iam persistence'
assert_areas 'markdown inside an app still runs that app' 'apps/api/README.md' 'api persistence'
assert_areas 'a docs folder inside app source is code' 'apps/web/src/features/docs/page.tsx' 'web'
assert_areas 'the query registry feeds persistence checks and their tests' \
  'config/persistence/custom-query-registry.txt' \
  'persistence setup'
assert_areas 'persistence scripts feed persistence checks and their tests' \
  'scripts/check-persistence-boundaries.sh' \
  'persistence setup'
assert_areas 'other scripts run the setup tests' 'scripts/ci/changed-areas.sh' 'setup'
assert_areas 'the Makefile runs everything' 'Makefile' "$all_areas"
assert_areas 'Compose runs everything' 'docker-compose.yml' "$all_areas"
assert_areas 'workflow changes run everything' '.github/workflows/tests.yml' "$all_areas"
assert_areas 'unknown paths run everything' '.gitignore' "$all_areas"
assert_areas 'an empty change list runs everything' '' "$all_areas"
assert_areas 'mixed changes run the union of their areas' \
  'README.md
apps/web/src/app/page.tsx' \
  'web setup'

echo "changed areas tests passed"
