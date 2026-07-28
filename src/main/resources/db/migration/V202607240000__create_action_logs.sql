CREATE TABLE action_logs (
    id                    UUID PRIMARY KEY,
    user_id               UUID,
    username              VARCHAR(100),
    start_time            TIMESTAMPTZ  NOT NULL,
    end_time              TIMESTAMPTZ  NOT NULL,
    duration              BIGINT       NOT NULL,
    path                  VARCHAR(500) NOT NULL,
    api_doc               VARCHAR(500),
    request_method        VARCHAR(20)  NOT NULL,
    ip_address            VARCHAR(64),
    user_agent            VARCHAR(512),
    request_data          TEXT,
    status_code           INTEGER      NOT NULL,
    error_message         TEXT,
    request_param         TEXT,
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX action_logs_user_time_idx
    ON action_logs (user_id, start_time DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX action_logs_status_time_idx
    ON action_logs (status_code, start_time DESC);
