-- Staging reads now exclude soft-deleted rows. The duplicate probe (correlated EXISTS) stays an
-- index-only scan only if the partial index predicate implies the query's filter, so the dedup
-- index is rebuilt to cover live rows only.
DROP INDEX IF EXISTS customer_import_staging_dedup_idx;

CREATE INDEX customer_import_staging_dedup_idx
    ON customer_import_staging (job_id, attempt_number, normalized_external_id, row_number)
    WHERE validation_passed AND deleted_at IS NULL;
