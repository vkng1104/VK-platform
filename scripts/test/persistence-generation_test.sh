#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
generate_script="$repository_root/scripts/generate-persistence.sh"
check_script="$repository_root/scripts/check-persistence-generation.sh"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT INT TERM
fixture_number=0

fail() {
  echo "persistence generation test failed: $1" >&2
  exit 1
}

assert_contains() {
  value="$1"
  expected="$2"
  case "$value" in
    *"$expected"*) ;;
    *) fail "expected '$expected' in '$value'" ;;
  esac
}

new_fixture() {
  fixture_number=$((fixture_number + 1))
  fixture="$test_directory/fixture-$fixture_number"
  mkdir -p "$fixture/config/persistence" "$fixture/tools"
  printf '%s\n' '# service|script|tracked_generated_path' \
    >"$fixture/config/persistence/generators.txt"
}

run_generate() {
  PERSISTENCE_REPOSITORY_ROOT="$fixture" "$generate_script" 2>&1
}

run_check() {
  PERSISTENCE_REPOSITORY_ROOT="$fixture" \
  PERSISTENCE_GENERATORS_PATH="$fixture/config/persistence/generators.txt" \
  PERSISTENCE_GENERATOR_SCRIPT="$generate_script" \
    "$check_script" 2>&1
}

write_generator() {
  generated_value="$1"
  printf '%s\n' \
    '#!/bin/sh' \
    'set -eu' \
    'mkdir -p "$PERSISTENCE_REPOSITORY_ROOT/generated"' \
    "printf '%s\\n' '$generated_value' >\"\$PERSISTENCE_REPOSITORY_ROOT/generated/client.go\"" \
    >"$fixture/tools/generate.sh"
  chmod +x "$fixture/tools/generate.sh"
}

initialize_git_fixture() {
  git -C "$fixture" init --quiet
  git -C "$fixture" config user.name "Persistence Test"
  git -C "$fixture" config user.email "persistence-test@example.invalid"
  git -C "$fixture" add .
  git -C "$fixture" commit --quiet -m "fixture"
}

new_fixture
output="$(run_generate)"
assert_contains "$output" "No persistence generators are registered yet."

new_fixture
printf '%s\n' 'iam|../unsafe.sh|-' >"$fixture/config/persistence/generators.txt"
if output="$(run_generate)"; then
  fail "unsafe generator path was accepted"
fi
assert_contains "$output" "unsafe generator script path"

new_fixture
write_generator 'stable generated source'
printf '%s\n' 'api|tools/generate.sh|generated' \
  >"$fixture/config/persistence/generators.txt"
output="$(run_generate)"
assert_contains "$output" "generators=1"
grep -Fq 'stable generated source' "$fixture/generated/client.go" ||
  fail "registered generator did not produce its output"

initialize_git_fixture
output="$(run_check)"
assert_contains "$output" "Persistence generation is reproducible."

new_fixture
write_generator 'baseline generated source'
printf '%s\n' 'api|tools/generate.sh|generated' \
  >"$fixture/config/persistence/generators.txt"
run_generate >/dev/null
initialize_git_fixture
write_generator 'changed generated source'
if output="$(run_check)"; then
  fail "generated-source drift was accepted"
fi
assert_contains "$output" "persistence generation drift detected"

new_fixture
write_generator 'new untracked generated source'
printf '%s\n' 'api|tools/generate.sh|generated' \
  >"$fixture/config/persistence/generators.txt"
initialize_git_fixture
if output="$(run_check)"; then
  fail "untracked generated output was accepted"
fi
assert_contains "$output" "persistence generation drift detected"

echo "persistence generation tests passed"
