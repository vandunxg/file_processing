-- The trigger for the *next* attempt is decided when a retry or recovery is requested, but the
-- attempt itself is only created when a worker claims the job. Without persisting it, a restart
-- between those two moments would lose whether the next run is a USER_RETRY, an ADMIN_RETRY or a
-- RECOVERY, and the attempt history would misreport why the job ran again.
ALTER TABLE processing_job
    ADD COLUMN next_attempt_trigger VARCHAR(30) NOT NULL DEFAULT 'INITIAL';

ALTER TABLE processing_job
    ADD CONSTRAINT processing_job_next_attempt_trigger_chk CHECK (
        next_attempt_trigger IN ('INITIAL', 'USER_RETRY', 'ADMIN_RETRY', 'RECOVERY')
    );
