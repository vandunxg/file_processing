-- Expand step for customer provenance.
--
-- `last_import_job_id` (added by V202609081025) becomes the writable provenance column while
-- `last_import_file_id` stays in place, unpopulated by new writes, until Stage C of the migration
-- strategy has backfilled it and the contract step can drop it.
ALTER TABLE customers
    ALTER COLUMN last_import_file_id DROP NOT NULL;

ALTER TABLE customers
    ADD COLUMN deleted_at TIMESTAMPTZ;

-- `external_id` is the globally unique business identity of a customer, so it stays unique across
-- soft-deleted rows too: a later import must reuse the existing identity instead of creating a
-- second customer for the same external_id.
CREATE INDEX customers_deleted_at_idx
    ON customers (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE INDEX customers_last_import_job_id_idx
    ON customers (last_import_job_id);
