-- Backfill: every file that existed before the lifecycle split gets its canonical processing job.
--
-- Without this, a file uploaded before the split has no job, so the job-scoped API cannot see it at
-- all: its error report becomes unreachable even though the object is still in the bucket, and the
-- processing state recorded on file_import turns into dead data.
--
-- Legacy PROCESSING is mapped to FAILED on purpose. No worker owns that state after the deployment
-- cutover, so treating it as running would leave a job nobody is executing and nothing would ever
-- finish it. FAILED is the honest description and it is retryable, which is the outcome the owner
-- wants.
INSERT INTO processing_job (
    id,
    import_file_id,
    owner_id,
    status,
    processed_rows,
    valid_rows,
    invalid_rows,
    inserted_rows,
    updated_rows,
    total_rows,
    progress_percent,
    current_attempt,
    error_code,
    error_summary,
    error_report_key,
    started_at,
    finished_at,
    heartbeat_at,
    next_attempt_trigger,
    created_by,
    created_at,
    last_modified_by,
    last_modified_at,
    version
)
SELECT
    gen_random_uuid(),
    file.id,
    file.owner_id,
    CASE file.processing_status
        WHEN 'COMPLETED'             THEN 'COMPLETED'
        WHEN 'COMPLETED_WITH_ERRORS' THEN 'COMPLETED_WITH_ERRORS'
        ELSE 'FAILED'
    END,
    file.processed_rows,
    file.valid_rows,
    file.invalid_rows,
    file.inserted_rows,
    file.updated_rows,
    -- The stored counters are the only record of how far the legacy run got, and for a finished run
    -- they are the final totals.
    CASE WHEN file.processing_status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
         THEN file.processed_rows END,
    CASE WHEN file.processing_status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
         THEN 100 END,
    1,
    CASE WHEN file.processing_status NOT IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
         THEN 'LEGACY_IMPORT_INCOMPLETE' END,
    CASE WHEN file.processing_status NOT IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
         THEN 'Import did not finish before the processing lifecycle was migrated' END,
    -- A report belongs only to a run that finished with rejected rows.
    CASE WHEN file.processing_status = 'COMPLETED_WITH_ERRORS' THEN file.error_report_key END,
    -- No start or finish time was ever recorded, so the row's own timestamps are used rather than
    -- inventing a history that never existed.
    file.created_at,
    file.last_modified_at,
    NULL,
    'INITIAL',
    'system',
    file.created_at,
    'system',
    file.last_modified_at,
    0
FROM file_import file
WHERE NOT EXISTS (
    SELECT 1 FROM processing_job job WHERE job.import_file_id = file.id
);

-- One attempt per backfilled job, so the history is not silently empty for legacy imports.
INSERT INTO processing_attempt (
    id,
    job_id,
    attempt_number,
    "trigger",
    status,
    started_at,
    finished_at,
    processed_rows,
    valid_rows,
    invalid_rows,
    inserted_rows,
    updated_rows,
    error_code,
    error_summary,
    created_by,
    created_at,
    last_modified_by,
    last_modified_at
)
SELECT
    gen_random_uuid(),
    job.id,
    1,
    'INITIAL',
    CASE WHEN job.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') THEN 'SUCCEEDED' ELSE 'FAILED' END,
    job.started_at,
    job.finished_at,
    job.processed_rows,
    job.valid_rows,
    job.invalid_rows,
    job.inserted_rows,
    job.updated_rows,
    job.error_code,
    job.error_summary,
    'system',
    job.created_at,
    'system',
    job.last_modified_at
FROM processing_job job
WHERE NOT EXISTS (
    SELECT 1 FROM processing_attempt attempt WHERE attempt.job_id = job.id
);

-- Customer provenance moves from the file to the job responsible for the state.
UPDATE customers customer
SET last_import_job_id = job.id
FROM processing_job job
WHERE customer.last_import_job_id IS NULL
  AND customer.last_import_file_id IS NOT NULL
  AND job.import_file_id = customer.last_import_file_id;
