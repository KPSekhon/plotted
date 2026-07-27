-- Plotted :: V6 :: Watchlists and preferences
--
-- Spec section 14.6.

CREATE TABLE watchlists (
    id           UUID         PRIMARY KEY,
    user_id      UUID         REFERENCES users (id) ON DELETE CASCADE,
    household_id UUID,
    name         VARCHAR(120) NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    visibility   VARCHAR(16)  NOT NULL DEFAULT 'private',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT watchlists_visibility_check CHECK (
        visibility IN ('private', 'household', 'link')
    )
);

CREATE INDEX watchlists_user_idx ON watchlists (user_id);

/* [jooq ignore start] */
-- Exactly one owner: a personal watchlist or a household watchlist, never both.
ALTER TABLE watchlists ADD CONSTRAINT watchlists_single_owner
    CHECK (num_nonnulls(user_id, household_id) = 1);

CREATE UNIQUE INDEX watchlists_one_default_per_user_idx ON watchlists (user_id)
    WHERE is_default AND user_id IS NOT NULL;
/* [jooq ignore stop] */

CREATE TABLE watchlist_items (
    id                UUID           PRIMARY KEY,
    watchlist_id      UUID           NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
    title_id          UUID           NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    -- 1 is the HIGHEST priority and 5 the lowest. Stated here because ambiguous
    -- priority direction produces optimiser bugs that are very hard to see.
    priority          SMALLINT       NOT NULL DEFAULT 3,
    status            VARCHAR(16)    NOT NULL DEFAULT 'pending',
    added_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    desired_by_date   DATE,
    maximum_price     NUMERIC(10, 2),
    preferred_context JSONB,
    notes             TEXT,
    source            VARCHAR(24)    NOT NULL DEFAULT 'manual',
    CONSTRAINT watchlist_items_unique UNIQUE (watchlist_id, title_id),
    CONSTRAINT watchlist_items_priority_range CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT watchlist_items_status_check CHECK (
        status IN ('pending', 'in_progress', 'completed', 'abandoned', 'unavailable')
    )
);

COMMENT ON COLUMN watchlist_items.priority IS
    'User-assigned priority. 1 = highest, 5 = lowest. Sort ascending for most important first.';

CREATE INDEX watchlist_items_lookup_idx ON watchlist_items (watchlist_id, status, priority);
CREATE INDEX watchlist_items_title_idx ON watchlist_items (title_id);

CREATE TABLE blocked_titles (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title_id   UUID        NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    reason     VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, title_id)
);

CREATE INDEX blocked_titles_title_idx ON blocked_titles (title_id);

CREATE TABLE user_genre_preferences (
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    genre_id       SMALLINT      NOT NULL REFERENCES genres (id),
    affinity_score NUMERIC(5, 4) NOT NULL,
    -- Low-confidence dimensions fall back to the population prior during
    -- ranking rather than being trusted (spec section 6.7).
    confidence     NUMERIC(5, 4) NOT NULL DEFAULT 0.0000,
    source         VARCHAR(24)   NOT NULL DEFAULT 'prior',
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, genre_id),
    CONSTRAINT user_genre_preferences_affinity_range CHECK (affinity_score BETWEEN 0 AND 1),
    CONSTRAINT user_genre_preferences_confidence_range CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT user_genre_preferences_source_check CHECK (
        source IN ('prior', 'pilot_season', 'implicit', 'manual')
    )
);

CREATE INDEX user_genre_preferences_genre_idx ON user_genre_preferences (genre_id);

CREATE TABLE user_preference_profile (
    user_id                   UUID          PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    novelty_preference        NUMERIC(5, 4) NOT NULL DEFAULT 0.4000,
    commitment_tolerance      NUMERIC(5, 4) NOT NULL DEFAULT 0.5000,
    popularity_preference     NUMERIC(5, 4) NOT NULL DEFAULT 0.5000,
    emotional_intensity_limit NUMERIC(5, 4) NOT NULL DEFAULT 0.7000,
    film_series_preference    NUMERIC(5, 4) NOT NULL DEFAULT 0.5000,
    subtitle_tolerance        NUMERIC(5, 4) NOT NULL DEFAULT 0.5000,
    preferred_runtime_minutes INTEGER,
    profile_confidence        NUMERIC(5, 4) NOT NULL DEFAULT 0.0000,
    profile_version           INTEGER       NOT NULL DEFAULT 1,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now()
);

/* [jooq ignore start] */
ALTER TABLE user_preference_profile ADD COLUMN profile_vector REAL[];
/* [jooq ignore stop] */
