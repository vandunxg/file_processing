-- Duplicate external IDs are now decided while reading the workspace instead of by a ranking pass
-- that wrote back to it.
--
-- The pass this removes updated every field-valid row of an attempt inside one transaction: 23.8s
-- for 1M rows against a 30s statement timeout, and it doubled the table on disk (1258 MB -> 2484 MB)
-- because a rewritten row cannot be a HOT update while duplicate_rank is indexed. Both costs were
-- paid at the very end of an import, after all the staging work had already succeeded.
--
-- What replaces it needs no column: a row is canonical when no earlier field-valid row of the same
-- attempt claimed its external ID, which customer_import_staging_dedup_idx answers as a single
-- index-only probe. That index is created by V202609081500 and is kept.
DROP INDEX IF EXISTS customer_import_staging_canonical_idx;

ALTER TABLE customer_import_staging DROP COLUMN IF EXISTS duplicate_rank;

-- Corrects the durability claim in V202609081500, which cannot be edited without breaking its
-- Flyway checksum. PostgreSQL truncates an UNLOGGED table during crash recovery, but a clean
-- restart keeps every row, so the engine is never a backstop for the PII held here. Three things
-- remove it: the runner clears the attempt it finishes, and -- because neither may turn a failed
-- PII delete into a failed import -- a periodic sweep deletes whatever no running attempt owns,
-- which is also what reclaims an attempt whose worker never came back.
COMMENT ON TABLE customer_import_staging IS
    'Attempt-scoped import workspace. UNLOGGED: contents survive a clean restart and are lost only '
    'to crash recovery, so PII removal relies on the runner plus the abandoned-workspace sweep, '
    'never on the engine. Duplicate external IDs are resolved on read via the dedup index.';

COMMENT ON TABLE customer_import_staging_issue IS
    'Validation issues for one import attempt, held only until the error report is published. '
    'Removed by the same runner and sweep that clear customer_import_staging.';
