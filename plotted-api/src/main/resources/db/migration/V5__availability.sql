-- Plotted :: V5 :: Availability
--
-- Spec section 14.5.
--
-- The original unique constraint on title_availability included a nullable
-- available_from. In Postgres NULL is never equal to NULL, so that constraint
-- permits unlimited duplicate rows for the most common case -- a title available
-- now with no known start date -- and duplicated availability rows inflate every
-- coverage number the optimiser depends on. A DATERANGE plus a GiST exclusion
-- constraint fixes it properly.

CREATE TABLE title_availability (
    id               UUID          PRIMARY KEY,
    title_id         UUID          NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    provider_id      UUID          NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    region_code      CHAR(2)       NOT NULL,
    access_type      VARCHAR(16)   NOT NULL,
    quality          VARCHAR(16),
    price            NUMERIC(10, 2),
    currency         CHAR(3),
    deep_link        TEXT,
    source           VARCHAR(32)   NOT NULL,
    source_checked_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Section 7.3: availability data will sometimes be wrong. Confidence and
    -- source_checked_at are load-bearing product features, not bookkeeping.
    confidence       NUMERIC(4, 3) NOT NULL DEFAULT 1.000,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT title_availability_access_type_check CHECK (
        access_type IN ('subscription', 'free', 'ads', 'rent', 'buy', 'library')
    ),
    CONSTRAINT title_availability_confidence_range CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT title_availability_price_requires_currency CHECK (
        price IS NULL OR currency IS NOT NULL
    )
);

-- Foreign-key indexes, unfiltered, for the cascade path. The partial indexes
-- below serve the query path and do not cover inactive rows.
CREATE INDEX title_availability_title_idx ON title_availability (title_id);
CREATE INDEX title_availability_provider_idx ON title_availability (provider_id);

/* [jooq ignore start] */
ALTER TABLE title_availability ADD COLUMN validity DATERANGE NOT NULL;

-- The constraint that makes duplicate availability rows unrepresentable.
ALTER TABLE title_availability ADD CONSTRAINT title_availability_no_overlap EXCLUDE USING gist (
    title_id    WITH =,
    provider_id WITH =,
    region_code WITH =,
    access_type WITH =,
    validity    WITH &&
);

CREATE INDEX title_availability_title_region_idx ON title_availability (title_id, region_code)
    WHERE active;
CREATE INDEX title_availability_provider_region_idx ON title_availability (provider_id, region_code)
    WHERE active;
CREATE INDEX title_availability_expiry_idx ON title_availability (upper(validity))
    WHERE active;
CREATE INDEX title_availability_staleness_idx ON title_availability (source_checked_at)
    WHERE active;
/* [jooq ignore stop] */

-- The Plot Armour training set, and the one asset in this project that cannot be
-- re-downloaded. Collection starts in Phase 2 and the risk model is not built
-- until Tier 3; retain indefinitely and back up separately (spec section 14.5).
CREATE TABLE availability_snapshots (
    id                UUID        PRIMARY KEY,
    title_id          UUID        NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    region_code       CHAR(2)     NOT NULL,
    captured_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    availability_hash CHAR(64)    NOT NULL,
    raw_summary       JSONB       NOT NULL
);

CREATE INDEX availability_snapshots_title_idx
    ON availability_snapshots (title_id, region_code, captured_at DESC);

CREATE TABLE availability_changes (
    id              UUID          PRIMARY KEY,
    title_id        UUID          NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    provider_id     UUID          NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    region_code     CHAR(2)       NOT NULL,
    change_type     VARCHAR(24)   NOT NULL,
    old_access_type VARCHAR(16),
    new_access_type VARCHAR(16),
    detected_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    effective_date  DATE,
    confidence      NUMERIC(4, 3) NOT NULL DEFAULT 1.000,
    CONSTRAINT availability_changes_type_check CHECK (
        change_type IN ('added', 'removed', 'access_type_changed', 'price_changed')
    )
);

CREATE INDEX availability_changes_title_idx ON availability_changes (title_id, detected_at DESC);
CREATE INDEX availability_changes_provider_idx ON availability_changes (provider_id, detected_at DESC);

-- User-reported corrections both improve the data and give an honest,
-- independently labelled way to measure how accurate the upstream feed is for
-- Canadian services (spec section 14.5).
CREATE TABLE availability_corrections (
    id             UUID        PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title_id       UUID        NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    provider_id    UUID        REFERENCES providers (id) ON DELETE SET NULL,
    region_code    CHAR(2)     NOT NULL,
    reported_state VARCHAR(32) NOT NULL,
    system_state   VARCHAR(32) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'open',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT availability_corrections_status_check CHECK (
        status IN ('open', 'confirmed', 'rejected', 'superseded')
    )
);

CREATE INDEX availability_corrections_user_idx ON availability_corrections (user_id, created_at DESC);
CREATE INDEX availability_corrections_title_idx ON availability_corrections (title_id, created_at DESC);
CREATE INDEX availability_corrections_provider_idx ON availability_corrections (provider_id);
