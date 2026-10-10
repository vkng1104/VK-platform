#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
agents_path="$repository_root/AGENTS.md"
delivery_skill_path="$repository_root/.agents/skills/feature-delivery/SKILL.md"
delivery_metadata_path="$repository_root/.agents/skills/feature-delivery/agents/openai.yaml"

fail() {
  echo "repository policy test failed: $1" >&2
  exit 1
}

assert_contains() {
  path="$1"
  expected="$2"
  message="$3"
  grep -Fq "$expected" "$path" || fail "$message"
}

assert_contains "$agents_path" \
  'Treat every pushed commit as immutable public history.' \
  'AGENTS.md does not protect pushed commits'
assert_contains "$agents_path" \
  'Add corrective implementation, test, documentation, merge, or revert commits instead' \
  'AGENTS.md does not require append-only corrections'
assert_contains "$agents_path" \
  'Do not rebase a published branch.' \
  'AGENTS.md does not require merge-based base updates'

assert_contains "$delivery_skill_path" \
  "Before the branch's first remote push, produce exactly two feature-specific commits" \
  'feature-delivery does not scope the two-commit rule to the initial push'
assert_contains "$delivery_skill_path" \
  'Never amend a pushed commit.' \
  'feature-delivery does not forbid amending published commits'
assert_contains "$delivery_skill_path" \
  'Never use `git push --force` or `git push --force-with-lease`' \
  'feature-delivery does not forbid force-pushing published branches'
assert_contains "$delivery_skill_path" \
  'Use a normal merge commit to incorporate a newer base branch' \
  'feature-delivery does not preserve history during base updates'
assert_contains "$delivery_skill_path" \
  'append a new commit' \
  'feature-delivery does not require append-only corrections'
assert_contains "$delivery_metadata_path" \
  'default_prompt: "Use $feature-delivery to deliver this feature with append-only pushed history."' \
  'feature-delivery metadata does not advertise append-only history'

echo "repository policy tests passed"
