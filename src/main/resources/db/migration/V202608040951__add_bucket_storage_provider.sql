ALTER TABLE file_import
  ADD COLUMN IF NOT EXISTS bucket VARCHAR(100);

ALTER TABLE file_import
  ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(20);

UPDATE file_import
SET bucket = 'file-processing', storage_provider = 'R2'
WHERE bucket IS NULL OR storage_provider IS NULL;

ALTER TABLE file_import
  ALTER COLUMN bucket SET NOT NULL,
  ALTER COLUMN storage_provider SET NOT NULL;
