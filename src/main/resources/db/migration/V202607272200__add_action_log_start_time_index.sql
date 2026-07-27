CREATE INDEX action_logs_start_time_idx
  ON action_logs (start_time DESC)
  WHERE deleted_at IS NULL;
