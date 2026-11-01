-- Renders one PostgreSQL schema as DBML for dbdiagram.io.
--
-- psql variables:
--   schema   the schema to render
--   service  the owning service, used to name the files of other schemas
--   project  the DBML project name, which is also the output file name
--
-- The output is deterministic: every list is sorted and nothing depends on
-- time, so committed files can be compared byte for byte.

CREATE FUNCTION pg_temp.dbml_identifier(identifier text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN identifier ~ '^[a-z_][a-z0-9_]*$' THEN identifier
    ELSE '"' || replace(identifier, '"', '\"') || '"'
  END
$$;

CREATE FUNCTION pg_temp.dbml_string(value text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
  SELECT '''' || replace(replace(value, '\', '\\'), '''', '\''') || ''''
$$;

CREATE FUNCTION pg_temp.dbml_type(type_name text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN short_name ~ '^[a-z_][a-z0-9_]*(\([0-9,]+\))?$' THEN short_name
    ELSE '"' || short_name || '"'
  END
  FROM (
    SELECT regexp_replace(regexp_replace(regexp_replace(regexp_replace(type_name,
      '^timestamp(\([0-9]+\))? with time zone', 'timestamptz\1'),
      '^timestamp(\([0-9]+\))? without time zone', 'timestamp\1'),
      '^time(\([0-9]+\))? with time zone', 'timetz\1'),
      '^character varying', 'varchar') AS short_name
  ) AS shortened
$$;

CREATE FUNCTION pg_temp.dbml_default(expression text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN expression ~ '^-?[0-9]+(\.[0-9]+)?$' OR expression IN ('true', 'false') THEN expression
    WHEN expression ~ '^''([^'']|'''')*''::[a-z][a-z0-9_ ]*(\[\])?$' THEN pg_temp.dbml_string(
      replace(substring(expression FROM '^''((?:[^'']|'''')*)''::'), '''''', ''''))
    ELSE '`' || expression || '`'
  END
$$;

WITH owned_tables AS (
  SELECT c.oid, c.relname, n.nspname
  FROM pg_class AS c
  JOIN pg_namespace AS n ON n.oid = c.relnamespace
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p')
),
column_keys AS (
  -- Primary keys and default-named unique constraints on a single column are
  -- shown on the column itself; every other key is listed under Indexes.
  SELECT con.conrelid, con.conkey[1] AS attnum, con.contype, con.conindid
  FROM pg_constraint AS con
  JOIN owned_tables AS t ON t.oid = con.conrelid
  JOIN pg_attribute AS a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
  WHERE cardinality(con.conkey) = 1
    AND (con.contype = 'p' OR (con.contype = 'u' AND con.conname = t.relname || '_' || a.attname || '_key'))
),
column_lines AS (
  SELECT
    a.attrelid,
    a.attnum,
    '  ' || pg_temp.dbml_identifier(a.attname) || ' '
      || pg_temp.dbml_type(format_type(a.atttypid, a.atttypmod))
      || coalesce(' [' || nullif(concat_ws(', ',
        CASE WHEN keys.is_primary THEN 'pk' END,
        CASE WHEN a.attidentity <> '' OR defaults.expression LIKE 'nextval(%' THEN 'increment' END,
        CASE WHEN a.attnotnull AND NOT keys.is_primary THEN 'not null' END,
        CASE WHEN keys.is_unique THEN 'unique' END,
        CASE WHEN a.attgenerated = '' AND defaults.expression NOT LIKE 'nextval(%'
          THEN 'default: ' || pg_temp.dbml_default(defaults.expression) END,
        CASE WHEN a.attgenerated <> '' OR col_description(a.attrelid, a.attnum) IS NOT NULL
          THEN 'note: ' || pg_temp.dbml_string(concat_ws('; ',
            col_description(a.attrelid, a.attnum),
            CASE WHEN a.attgenerated <> '' THEN 'generated as ' || defaults.expression END))
        END
      ), '') || ']', '') AS line
  FROM pg_attribute AS a
  JOIN owned_tables AS t ON t.oid = a.attrelid
  LEFT JOIN LATERAL (
    SELECT pg_get_expr(d.adbin, d.adrelid) AS expression
    FROM pg_attrdef AS d
    WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum
  ) AS defaults ON true
  CROSS JOIN LATERAL (
    SELECT coalesce(bool_or(k.contype = 'p'), false) AS is_primary,
      coalesce(bool_or(k.contype = 'u'), false) AS is_unique
    FROM column_keys AS k
    WHERE k.conrelid = a.attrelid AND k.attnum = a.attnum
  ) AS keys
  WHERE a.attnum > 0 AND NOT a.attisdropped
),
index_lines AS (
  SELECT
    i.indrelid,
    i.indisprimary,
    index_class.relname AS index_name,
    '    ' || CASE
      WHEN index_keys.key_count = 1 AND index_keys.key_list NOT LIKE '`%' THEN index_keys.key_list
      ELSE '(' || index_keys.key_list || ')'
    END
      || ' [' || concat_ws(', ',
        CASE WHEN i.indisprimary THEN 'pk' END,
        CASE WHEN i.indisunique AND NOT i.indisprimary THEN 'unique' END,
        CASE WHEN NOT i.indisprimary THEN 'name: ' || pg_temp.dbml_string(index_class.relname) END,
        CASE WHEN access_method.amname = 'hash' THEN 'type: hash' END,
        'note: ' || pg_temp.dbml_string(nullif(concat_ws(' ',
          CASE WHEN access_method.amname NOT IN ('btree', 'hash') THEN 'USING ' || access_method.amname END,
          'WHERE ' || pg_get_expr(i.indpred, i.indrelid)), ''))
      ) || ']' AS line
  FROM pg_index AS i
  JOIN owned_tables AS t ON t.oid = i.indrelid
  JOIN pg_class AS index_class ON index_class.oid = i.indexrelid
  JOIN pg_am AS access_method ON access_method.oid = index_class.relam
  CROSS JOIN LATERAL (
    SELECT count(*) AS key_count, string_agg(
      CASE
        WHEN i.indkey[ordinal - 1] = 0
          THEN '`' || pg_get_indexdef(i.indexrelid, ordinal, true) || '`'
        WHEN (i.indoption[ordinal - 1] & 3) = 0 THEN pg_temp.dbml_identifier(a.attname)
        ELSE '`' || pg_temp.dbml_identifier(a.attname) || CASE (i.indoption[ordinal - 1] & 3)
          WHEN 1 THEN ' DESC NULLS LAST'
          WHEN 2 THEN ' NULLS FIRST'
          ELSE ' DESC'
        END || '`'
      END, ', ' ORDER BY ordinal) AS key_list
    FROM generate_series(1, i.indnkeyatts) AS ordinal
    LEFT JOIN pg_attribute AS a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[ordinal - 1]
  ) AS index_keys
  WHERE NOT EXISTS (SELECT 1 FROM column_keys AS k WHERE k.conindid = i.indexrelid)
),
table_notes AS (
  SELECT t.oid, nullif(concat_ws(E'\n\n',
    obj_description(t.oid, 'pg_class'),
    (SELECT 'Checks:' || E'\n' || string_agg(
        '- ' || con.conname || ': ' || regexp_replace(pg_get_constraintdef(con.oid, true), '\s+', ' ', 'g'),
        E'\n' ORDER BY con.conname)
     FROM pg_constraint AS con
     WHERE con.conrelid = t.oid AND con.contype = 'c')
  ), '') AS note
  FROM owned_tables AS t
),
table_blocks AS (
  SELECT t.relname,
    'Table ' || pg_temp.dbml_identifier(t.nspname) || '.' || pg_temp.dbml_identifier(t.relname) || E' {\n'
      || concat_ws(E'\n\n',
        (SELECT string_agg(c.line, E'\n' ORDER BY c.attnum) FROM column_lines AS c WHERE c.attrelid = t.oid),
        (SELECT E'  Indexes {\n' || string_agg(i.line, E'\n' ORDER BY i.indisprimary DESC, i.index_name) || E'\n  }'
         FROM index_lines AS i WHERE i.indrelid = t.oid),
        (SELECT '  Note: ' || repeat(chr(39), 3) || E'\n'
            || string_agg(CASE WHEN l.content = '' THEN '' ELSE '    ' || replace(replace(l.content, '\', '\\'),
              repeat(chr(39), 3), repeat('\' || chr(39), 3)) END, E'\n' ORDER BY l.ordinal)
            || E'\n  ' || repeat(chr(39), 3)
         FROM table_notes AS n
         CROSS JOIN LATERAL regexp_split_to_table(n.note, E'\n') WITH ORDINALITY AS l(content, ordinal)
         WHERE n.oid = t.oid)
      ) || E'\n}' AS block
  FROM owned_tables AS t
),
foreign_keys AS (
  SELECT con.conname, con.conrelid, con.confrelid, con.conkey, con.confkey, con.confdeltype, con.confupdtype,
    t.nspname AS source_schema, t.relname AS source_table,
    target_namespace.nspname AS target_schema, target_class.relname AS target_table
  FROM pg_constraint AS con
  JOIN owned_tables AS t ON t.oid = con.conrelid
  JOIN pg_class AS target_class ON target_class.oid = con.confrelid
  JOIN pg_namespace AS target_namespace ON target_namespace.oid = target_class.relnamespace
  WHERE con.contype = 'f'
),
stub_columns AS (
  -- Tables in other schemas that this schema references, reduced to the
  -- referenced columns so dbdiagram.io can draw the link.
  SELECT DISTINCT fk.target_schema, fk.target_table, a.attnum, a.attname,
    format_type(a.atttypid, a.atttypmod) AS type_name,
    EXISTS (
      SELECT 1 FROM pg_constraint AS pk
      WHERE pk.conrelid = fk.confrelid AND pk.contype = 'p' AND pk.conkey = ARRAY[a.attnum]
    ) AS is_primary
  FROM foreign_keys AS fk
  CROSS JOIN LATERAL unnest(fk.confkey) AS referenced(attnum)
  JOIN pg_attribute AS a ON a.attrelid = fk.confrelid AND a.attnum = referenced.attnum
  WHERE fk.target_schema <> :'schema'
),
stub_blocks AS (
  SELECT s.target_schema, s.target_table,
    'Table ' || pg_temp.dbml_identifier(s.target_schema) || '.' || pg_temp.dbml_identifier(s.target_table)
      || E' [headercolor: #BDBDBD] {\n'
      || string_agg('  ' || pg_temp.dbml_identifier(s.attname) || ' ' || pg_temp.dbml_type(s.type_name)
        || CASE WHEN s.is_primary THEN ' [pk]' ELSE '' END, E'\n' ORDER BY s.attnum)
      || E'\n\n  Note: ' || pg_temp.dbml_string('Defined in ' || CASE
        WHEN starts_with(s.target_schema, :'service' || '_') THEN s.target_schema
        ELSE :'service' || '_' || s.target_schema
      END || '.dbml')
      || E'\n}' AS block
  FROM stub_columns AS s
  GROUP BY s.target_schema, s.target_table
),
reference_lines AS (
  SELECT fk.source_table, fk.conname,
    'Ref ' || pg_temp.dbml_identifier(fk.conname) || ': '
      || pg_temp.dbml_identifier(fk.source_schema) || '.' || pg_temp.dbml_identifier(fk.source_table)
      || '.' || source_columns.list
      || CASE WHEN one_to_one.is_unique THEN ' - ' ELSE ' > ' END
      || pg_temp.dbml_identifier(fk.target_schema) || '.' || pg_temp.dbml_identifier(fk.target_table)
      || '.' || target_columns.list
      || coalesce(' [' || nullif(concat_ws(', ',
        'delete: ' || CASE fk.confdeltype
          WHEN 'c' THEN 'cascade' WHEN 'r' THEN 'restrict' WHEN 'n' THEN 'set null' WHEN 'd' THEN 'set default'
        END,
        'update: ' || CASE fk.confupdtype
          WHEN 'c' THEN 'cascade' WHEN 'r' THEN 'restrict' WHEN 'n' THEN 'set null' WHEN 'd' THEN 'set default'
        END
      ), '') || ']', '') AS line
  FROM foreign_keys AS fk
  CROSS JOIN LATERAL (
    SELECT CASE WHEN count(*) = 1 THEN min(pg_temp.dbml_identifier(a.attname))
      ELSE '(' || string_agg(pg_temp.dbml_identifier(a.attname), ', ' ORDER BY fk_key.ordinal) || ')'
    END AS list
    FROM unnest(fk.conkey) WITH ORDINALITY AS fk_key(attnum, ordinal)
    JOIN pg_attribute AS a ON a.attrelid = fk.conrelid AND a.attnum = fk_key.attnum
  ) AS source_columns
  CROSS JOIN LATERAL (
    SELECT CASE WHEN count(*) = 1 THEN min(pg_temp.dbml_identifier(a.attname))
      ELSE '(' || string_agg(pg_temp.dbml_identifier(a.attname), ', ' ORDER BY fk_key.ordinal) || ')'
    END AS list
    FROM unnest(fk.confkey) WITH ORDINALITY AS fk_key(attnum, ordinal)
    JOIN pg_attribute AS a ON a.attrelid = fk.confrelid AND a.attnum = fk_key.attnum
  ) AS target_columns
  CROSS JOIN LATERAL (
    -- A foreign key whose columns are also a full unique key is one-to-one.
    SELECT EXISTS (
      SELECT 1
      FROM pg_index AS u
      WHERE u.indrelid = fk.conrelid AND u.indisunique AND u.indpred IS NULL
        AND ARRAY(SELECT k FROM unnest((string_to_array(u.indkey::text, ' ')::int2[])[1:u.indnkeyatts]) AS k ORDER BY k)
          = ARRAY(SELECT k FROM unnest(fk.conkey) AS k ORDER BY k)
    ) AS is_unique
  ) AS one_to_one
)
SELECT concat_ws(E'\n\n',
  'Project ' || pg_temp.dbml_identifier(:'project') || E' {\n  database_type: ''PostgreSQL''\n}',
  (SELECT string_agg(block, E'\n\n' ORDER BY relname) FROM table_blocks),
  (SELECT string_agg(block, E'\n\n' ORDER BY target_schema, target_table) FROM stub_blocks),
  (SELECT string_agg(line, E'\n' ORDER BY source_table, conname) FROM reference_lines)
);
