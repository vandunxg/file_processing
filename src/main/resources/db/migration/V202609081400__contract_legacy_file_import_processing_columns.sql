-- Contract step: the processing lifecycle now lives in processing_job/processing_attempt and
-- customer provenance in customers.last_import_job_id, so the legacy columns go.
--
-- The drop is irreversible, so it verifies the backfill first instead of trusting that it ran.
-- A migration that fails here leaves every legacy value in place and can be retried once the
-- backfill has been completed; a migration that dropped first would destroy the only copy.
DO $$
DECLARE
    files_without_job BIGINT;
    customers_without_job BIGINT;
BEGIN
    SELECT count(*) INTO files_without_job
    FROM file_import file
    WHERE NOT EXISTS (
        SELECT 1 FROM processing_job job WHERE job.import_file_id = file.id
    );

    IF files_without_job > 0 THEN
        RAISE EXCEPTION
            'Refusing to drop legacy processing columns: % file_import row(s) have no processing_job. Re-run the backfill first.',
            files_without_job;
    END IF;

    SELECT count(*) INTO customers_without_job
    FROM customers
    WHERE last_import_file_id IS NOT NULL
      AND last_import_job_id IS NULL;

    IF customers_without_job > 0 THEN
        RAISE EXCEPTION
            'Refusing to drop last_import_file_id: % customer row(s) still have no last_import_job_id. Re-run the provenance backfill first.',
            customers_without_job;
    END IF;
END $$;

ALTER TABLE file_import
    DROP COLUMN processing_status,
    DROP COLUMN processed_rows,
    DROP COLUMN valid_rows,
    DROP COLUMN invalid_rows,
    DROP COLUMN inserted_rows,
    DROP COLUMN updated_rows,
    DROP COLUMN error_report_key;

ALTER TABLE customers
    DROP COLUMN last_import_file_id;
