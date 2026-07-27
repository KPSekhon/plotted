-- Plotted :: V2 :: Identity
--
-- Spec section 14.1, plus two tables the spec implies but does not define:
--   * refresh_tokens -- section 18 asks for "rotating refresh tokens with reuse
--     detection", which needs server-side state. Postgres rather than Redis so
--     that authentication has no additional hard runtime dependency.
--   * audit_log      -- section 14.14.

CREATE TABLE users (
    id                 UUID         PRIMARY KEY,
    -- Declared VARCHAR so the jOOQ generator can model it, then converted to
    -- CITEXT in the fenced ALTER below. 320 is the maximum length of an
    -- addr-spec. The uniqueness guarantee is case-insensitive.
    email              VARCHAR(320) NOT NULL,
    password_hash      VARCHAR(255),
    display_name       VARCHAR(120) NOT NULL,
    region_code        CHAR(2)      NOT NULL DEFAULT 'CA',
    timezone           VARCHAR(64)  NOT NULL DEFAULT 'America/Toronto',
    preferred_currency CHAR(3)      NOT NULL DEFAULT 'CAD',
    onboarding_status  VARCHAR(32)  NOT NULL DEFAULT 'registered',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_region_code_check CHECK (region_code = upper(region_code)),
    CONSTRAINT users_onboarding_status_check CHECK (
        onboarding_status IN ('registered', 'region_selected', 'services_selected',
                              'pilot_started', 'pilot_complete', 'active')
    )
);

/* [jooq ignore start] */
ALTER TABLE users ALTER COLUMN email TYPE CITEXT;
/* [jooq ignore stop] */

COMMENT ON TABLE users IS 'Account holders. Soft-deleted via deleted_at; DeleteAccountWorkflow performs the hard delete.';

CREATE TABLE external_accounts (
    id                      UUID        PRIMARY KEY,
    user_id                 UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider                VARCHAR(32) NOT NULL,
    external_user_id        VARCHAR(255) NOT NULL,
    -- Envelope-encrypted. encryption_key_id names the data-encryption key so
    -- that keys can be rotated without re-reading every row at once.
    access_token_encrypted  BYTEA,
    refresh_token_encrypted BYTEA,
    encryption_key_id       VARCHAR(64) NOT NULL,
    token_expires_at        TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT external_accounts_unique UNIQUE (provider, external_user_id)
);

/* [jooq ignore start] */
ALTER TABLE external_accounts ADD COLUMN scopes TEXT[] NOT NULL DEFAULT '{}';
/* [jooq ignore stop] */

CREATE INDEX external_accounts_user_idx ON external_accounts (user_id);

CREATE TABLE user_settings (
    user_id                         UUID          PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    maximum_monthly_budget          NUMERIC(10, 2),
    maximum_active_services         INTEGER,
    maximum_monthly_switches        INTEGER,
    default_available_minutes       INTEGER,
    default_access_policy           VARCHAR(32)   NOT NULL DEFAULT 'active_subscriptions_only',
    default_novelty_preference      NUMERIC(4, 3) NOT NULL DEFAULT 0.400,
    default_commitment_preference   VARCHAR(16)   NOT NULL DEFAULT 'medium',
    allow_paid_rentals              BOOLEAN       NOT NULL DEFAULT FALSE,
    maximum_rental_price            NUMERIC(10, 2),
    allow_physical_media            BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Section 6.3: the estimated weekly viewing capacity drives cancellation
    -- advice, so the user must be able to see and correct it.
    weekly_viewing_minutes_override INTEGER,
    notification_preferences        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    updated_at                      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT user_settings_access_policy_check CHECK (
        default_access_policy IN ('active_subscriptions_only', 'include_free', 'all_access')
    ),
    CONSTRAINT user_settings_commitment_check CHECK (
        default_commitment_preference IN ('low', 'medium', 'high')
    ),
    CONSTRAINT user_settings_novelty_range CHECK (default_novelty_preference BETWEEN 0 AND 1),
    CONSTRAINT user_settings_minutes_positive CHECK (
        default_available_minutes IS NULL OR default_available_minutes > 0
    )
);

-- Rotating refresh tokens with reuse detection.
--
-- A refresh token belongs to a family. Rotation issues a successor and stamps
-- used_at on the predecessor. Presenting an already-used token means the token
-- leaked, so the entire family is revoked rather than just that one token.
CREATE TABLE refresh_tokens (
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id       UUID        NOT NULL,
    -- SHA-256 of the opaque token, hex encoded. The token itself is never stored.
    token_hash      CHAR(64)    NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(32),
    user_agent_hash CHAR(64),
    ip_hash         CHAR(64),
    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_revoked_reason_check CHECK (
        revoked_reason IS NULL OR revoked_reason IN ('logout', 'reuse_detected', 'rotated', 'account_deleted')
    )
);

CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

-- Foreign-key index. Postgres does not create one automatically, and its absence
-- shows up first as a slow cascading delete. Deliberately unfiltered: the
-- partial index below is for the lookup path, not for the cascade.
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

/* [jooq ignore start] */
CREATE INDEX refresh_tokens_active_idx ON refresh_tokens (user_id, expires_at)
    WHERE revoked_at IS NULL AND used_at IS NULL;
/* [jooq ignore stop] */

CREATE TABLE audit_log (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id UUID        REFERENCES users (id) ON DELETE SET NULL,
    action        VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id   UUID,
    before_state  JSONB,
    after_state   JSONB,
    ip_hash       CHAR(64),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_actor_idx ON audit_log (actor_user_id, occurred_at DESC);
CREATE INDEX audit_log_resource_idx ON audit_log (resource_type, resource_id, occurred_at DESC);
