-- Plotted :: V4 :: Providers and subscriptions
--
-- Spec section 14.4.
--
-- Temporal correctness note: the original design keyed plan pricing on
-- (provider, region, name, effective_from), which permits two overlapping price
-- periods for the same plan and silently corrupts every historical cost
-- calculation. A DATERANGE with a GiST exclusion constraint makes that state
-- unrepresentable. The EXCLUDE clauses are fenced because the jOOQ parser has no
-- model for them; Postgres applies them normally.

CREATE TABLE providers (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    logo_url      TEXT,
    provider_type VARCHAR(16)  NOT NULL,
    website_url   TEXT,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT providers_name_unique UNIQUE (name),
    CONSTRAINT providers_slug_unique UNIQUE (slug),
    CONSTRAINT providers_type_check CHECK (
        provider_type IN ('subscription', 'free', 'transactional', 'library', 'linear')
    )
);

CREATE TABLE provider_plans (
    id                   UUID          PRIMARY KEY,
    provider_id          UUID          NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    region_code          CHAR(2)       NOT NULL,
    name                 VARCHAR(120)  NOT NULL,
    billing_period       VARCHAR(16)   NOT NULL,
    price                NUMERIC(10, 2) NOT NULL,
    currency             CHAR(3)       NOT NULL DEFAULT 'CAD',
    ad_supported         BOOLEAN       NOT NULL DEFAULT FALSE,
    simultaneous_streams INTEGER,
    CONSTRAINT provider_plans_billing_period_check CHECK (
        billing_period IN ('monthly', 'annual', 'quarterly')
    ),
    CONSTRAINT provider_plans_price_non_negative CHECK (price >= 0)
);

-- validity is a half-open range: [valid from, valid until). An unbounded upper
-- bound means "current". Prices are manually curated from public pricing pages
-- (spec section 7.1), and the exclusion constraint is what stops two overlapping
-- price periods for the same plan from ever existing.
/* [jooq ignore start] */
ALTER TABLE provider_plans ADD COLUMN validity DATERANGE NOT NULL;

ALTER TABLE provider_plans ADD CONSTRAINT provider_plans_no_overlap EXCLUDE USING gist (
    provider_id WITH =,
    region_code WITH =,
    name        WITH =,
    validity    WITH &&
);
/* [jooq ignore stop] */

CREATE INDEX provider_plans_provider_region_idx ON provider_plans (provider_id, region_code);

CREATE TABLE user_subscriptions (
    id                 UUID           PRIMARY KEY,
    user_id            UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    household_id       UUID,
    provider_plan_id   UUID           NOT NULL REFERENCES provider_plans (id),
    status             VARCHAR(16)    NOT NULL DEFAULT 'active',
    started_on         DATE           NOT NULL,
    renews_on          DATE,
    commitment_ends_on DATE,
    cancelled_on       DATE,
    actual_price       NUMERIC(10, 2),
    currency           CHAR(3)        NOT NULL DEFAULT 'CAD',
    auto_renews        BOOLEAN        NOT NULL DEFAULT TRUE,
    cannot_cancel      BOOLEAN        NOT NULL DEFAULT FALSE,
    notes              TEXT,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT user_subscriptions_status_check CHECK (
        status IN ('active', 'paused', 'cancelled', 'trial', 'lapsed')
    ),
    CONSTRAINT user_subscriptions_price_non_negative CHECK (
        actual_price IS NULL OR actual_price >= 0
    )
);

CREATE INDEX user_subscriptions_plan_idx ON user_subscriptions (provider_plan_id);
CREATE INDEX user_subscriptions_user_idx ON user_subscriptions (user_id);

/* [jooq ignore start] */
CREATE INDEX user_subscriptions_renewal_idx ON user_subscriptions (user_id, renews_on)
    WHERE status = 'active';
/* [jooq ignore stop] */

CREATE TABLE subscription_billing_periods (
    id                    UUID           PRIMARY KEY,
    subscription_id       UUID           NOT NULL REFERENCES user_subscriptions (id) ON DELETE CASCADE,
    amount                NUMERIC(10, 2) NOT NULL,
    currency              CHAR(3)        NOT NULL DEFAULT 'CAD',
    payment_status        VARCHAR(16)    NOT NULL DEFAULT 'paid',
    usage_minutes         INTEGER        NOT NULL DEFAULT 0,
    completed_title_count INTEGER        NOT NULL DEFAULT 0,
    CONSTRAINT subscription_billing_periods_status_check CHECK (
        payment_status IN ('paid', 'pending', 'refunded', 'failed')
    )
);

/* [jooq ignore start] */
ALTER TABLE subscription_billing_periods ADD COLUMN period DATERANGE NOT NULL;

ALTER TABLE subscription_billing_periods
    ADD CONSTRAINT subscription_billing_periods_no_overlap EXCLUDE USING gist (
        subscription_id WITH =,
        period          WITH &&
    );
/* [jooq ignore stop] */
