#!/bin/sh

set -eu

repository_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
generate_script="$repository_root/scripts/generate-dbdiagram.sh"
check_script="$repository_root/scripts/check-dbdiagram.sh"
fixture_source="$repository_root/scripts/test/fixtures/dbdiagram"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT INT TERM
fixture_number=0

fail() {
  echo "dbdiagram test failed: $1" >&2
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
  cp -R "$fixture_source" "$fixture"
}

run_generate() {
  DBDIAGRAM_REPOSITORY_ROOT="$fixture" \
  DBDIAGRAM_MANIFEST_PATH="$fixture/services.txt" \
  DBDIAGRAM_OUTPUT_ROOT="$test_directory/generated-$fixture_number" \
    "$generate_script" 2>&1
}

run_check() {
  DBDIAGRAM_REPOSITORY_ROOT="$fixture" \
  DBDIAGRAM_MANIFEST_PATH="$fixture/services.txt" \
    "$check_script" 2>&1
}

# Migrations replay in tool order (V10 after V2, .down.sql ignored), every schema
# with tables gets one file, and the output matches the reviewed fixtures.
new_fixture
output="$(run_generate)" || fail "generation failed: $output"
for diagram_directory in shop/dbdiagram ledger/dbdiagram; do
  diff -ru "$fixture_source/$diagram_directory" "$test_directory/generated-$fixture_number/$diagram_directory" ||
    fail "$diagram_directory differs from the reviewed fixtures"
done

output="$(run_check)" || fail "check rejected current diagrams: $output"
assert_contains "$output" "dbdiagram files match their migrations"

new_fixture
printf '%s\n' 'ALTER TABLE shop_ordering.orders ADD COLUMN note TEXT;' \
  >"$fixture/shop/migrations/V11__add_order_note.sql"
if output="$(run_check)"; then
  fail "check accepted a migration without a regenerated diagram"
fi
assert_contains "$output" "shop_ordering.dbml"
assert_contains "$output" "make dbdiagram"

new_fixture
printf '%s\n' "INSERT INTO shop_catalog.products (sku, name) VALUES ('sku-1', 'Sample');" \
  >"$fixture/shop/migrations/V11__seed_sample_product.sql"
output="$(run_check)" || fail "check rejected a data-only migration: $output"

new_fixture
printf '%s\n' '// stale' >"$fixture/shop/dbdiagram/shop_archive.dbml"
if output="$(run_check)"; then
  fail "check accepted a diagram whose schema no longer exists"
fi
assert_contains "$output" "shop_archive.dbml"

new_fixture
printf '%s\n' 'SELECT 1;' >"$fixture/shop/migrations/V2_1__patch.sql"
if output="$(run_generate)"; then
  fail "generation accepted a migration it cannot order"
fi
assert_contains "$output" "V2_1__patch.sql"

echo "dbdiagram tests passed"
