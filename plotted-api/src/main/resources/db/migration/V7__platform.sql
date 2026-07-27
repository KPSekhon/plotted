-- Plotted :: V7 :: Platform plumbing
--
-- Spec section 14.12. The outbox exists before anything writes to it on purpose:
-- it is the mechanism that keeps a database commit and the workflow or
-- notification it triggers from diverging, and retrofitting it later means
-- rewriting every write path that should have used it.

CREATE TABLE outbox (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0,
    last_error     TEXT
);

/* [jooq ignore start] */
CREATE INDEX outbox_unpublished_idx ON outbox (created_at)
    WHERE published_at IS NULL;
/* [jooq ignore stop] */

CREATE TABLE alerts (
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    alert_type      VARCHAR(48) NOT NULL,
    severity        VARCHAR(16) NOT NULL DEFAULT 'info',
    title_id        UUID        REFERENCES titles (id) ON DELETE CASCADE,
    subscription_id UUID        REFERENCES user_subscriptions (id) ON DELETE CASCADE,
    message         TEXT        NOT NULL,
    action_payload  JSONB,
    status          VARCHAR(16) NOT NULL DEFAULT 'unread',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    CONSTRAINT alerts_severity_check CHECK (severity IN ('info', 'warning', 'urgent')),
    CONSTRAINT alerts_status_check CHECK (status IN ('unread', 'read', 'dismissed', 'expired'))
);

-- Foreign-key indexes for the cascade path; the partial index below is for the
-- "what is waiting for me" query and does not cover read or dismissed alerts.
CREATE INDEX alerts_user_idx ON alerts (user_id);
CREATE INDEX alerts_title_idx ON alerts (title_id);
CREATE INDEX alerts_subscription_idx ON alerts (subscription_id);

/* [jooq ignore start] */
CREATE INDEX alerts_unread_idx ON alerts (user_id, created_at DESC)
    WHERE status = 'unread';
/* [jooq ignore stop] */

CREATE TABLE workflow_executions (
    id              UUID        PRIMARY KEY,
    workflow_id     VARCHAR(255) NOT NULL,
    workflow_type   VARCHAR(64) NOT NULL,
    entity_type     VARCHAR(64) NOT NULL,
    entity_id       UUID        NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'running',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    retry_count     INTEGER     NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    CONSTRAINT workflow_executions_workflow_id_unique UNIQUE (workflow_id),
    CONSTRAINT workflow_executions_status_check CHECK (
        status IN ('running', 'completed', 'failed', 'cancelled', 'timed_out')
    )
);

CREATE INDEX workflow_executions_entity_idx ON workflow_executions (entity_type, entity_id, started_at DESC);
