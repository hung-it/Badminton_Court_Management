\set ON_ERROR_STOP on
SELECT string_agg(format(
  'SELECT %L AS table_name, count(*) AS row_count, md5(coalesce(string_agg(to_jsonb(t)::text, '''' ORDER BY to_jsonb(t)::text), '''')) AS checksum FROM %I.%I t',
  tablename, schemaname, tablename), ' UNION ALL ' ORDER BY tablename)
FROM pg_tables WHERE schemaname='public'
\gexec
