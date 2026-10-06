#!/bin/sh

set -eu

case "${SOURCE_ENV:-}" in
  development)
    source_database_url="${SEED_DEVELOPMENT_DATABASE_URL:-}"
    ;;
  production)
    source_database_url="${SEED_PRODUCTION_DATABASE_URL:-}"
    ;;
  *)
    echo "SOURCE_ENV must be either development or production." >&2
    exit 2
    ;;
esac

if [ -z "$source_database_url" ]; then
  echo "No database URL is configured for the $SOURCE_ENV environment." >&2
  exit 2
fi

local_database_url="${LOCAL_DATABASE_URL:-}"
if [ -z "$local_database_url" ]; then
  echo "LOCAL_DATABASE_URL is required." >&2
  exit 2
fi

if [ "$source_database_url" = "$local_database_url" ]; then
  echo "The source and local database URLs must be different." >&2
  exit 2
fi

dump_path="$(mktemp)"
trap 'rm -f "$dump_path"' EXIT INT TERM

echo "Exporting data from the $SOURCE_ENV database..."
pg_dump \
  --dbname="$source_database_url" \
  --format=custom \
  --data-only \
  --no-owner \
  --no-privileges \
  --exclude-table-data=public.schema_migrations \
  --file="$dump_path"

echo "Clearing local application data..."
psql "$local_database_url" --set=ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE
  table_list text;
BEGIN
  SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
  INTO table_list
  FROM pg_tables
  WHERE schemaname = 'public'
    AND tablename <> 'schema_migrations';

  IF table_list IS NOT NULL THEN
    EXECUTE 'TRUNCATE TABLE ' || table_list || ' RESTART IDENTITY CASCADE';
  END IF;
END
$$;
SQL

echo "Importing $SOURCE_ENV data into the local database..."
pg_restore \
  --dbname="$local_database_url" \
  --data-only \
  --no-owner \
  --no-privileges \
  --disable-triggers \
  --exit-on-error \
  --single-transaction \
  "$dump_path"

echo "Local database seeded from $SOURCE_ENV."
