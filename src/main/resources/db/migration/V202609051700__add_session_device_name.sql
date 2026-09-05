-- AUTH-AC-05.10: a session records the device it was opened from, so the
-- "my sessions" list can name each entry instead of showing a raw user agent.
ALTER TABLE auth_refresh_sessions
    ADD COLUMN device_name VARCHAR(100);
