-- `file_import` predates the project-wide soft-delete rule. Retention cleanup needs to retire a
-- file without erasing the processing history that references it, so the row is marked rather than
-- removed.
ALTER TABLE file_import
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX file_import_deleted_at_idx
    ON file_import (deleted_at) WHERE deleted_at IS NOT NULL;
