-- Plotted :: V3 :: Catalogue
--
-- Spec section 14.3. Title metadata originates from TMDB; see docs/data-sources.md
-- for attribution obligations.

CREATE TABLE titles (
    id                  UUID         PRIMARY KEY,
    external_source     VARCHAR(16)  NOT NULL DEFAULT 'tmdb',
    external_id         VARCHAR(64)  NOT NULL,
    media_type          VARCHAR(16)  NOT NULL,
    name                VARCHAR(500) NOT NULL,
    original_name       VARCHAR(500),
    overview            TEXT,
    release_date        DATE,
    original_language   VARCHAR(16),
    content_rating      VARCHAR(16),
    poster_url          TEXT,
    backdrop_url        TEXT,
    popularity_score    NUMERIC(10, 4),
    community_rating    NUMERIC(4, 2),
    vote_count          INTEGER,
    metadata_status     VARCHAR(16)  NOT NULL DEFAULT 'stub',
    metadata_updated_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT titles_external_unique UNIQUE (external_source, external_id),
    CONSTRAINT titles_media_type_check CHECK (media_type IN ('movie', 'series')),
    CONSTRAINT titles_metadata_status_check CHECK (
        metadata_status IN ('stub', 'partial', 'complete', 'failed')
    )
);

-- Derived columns. Both use expressions the jOOQ parser does not model, so they
-- are added out of band; nothing in Phase 1 selects them by name.
/* [jooq ignore start] */
ALTER TABLE titles ADD COLUMN release_year INTEGER
    GENERATED ALWAYS AS (CAST(EXTRACT(YEAR FROM release_date) AS INTEGER)) STORED;

ALTER TABLE titles ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(original_name, ''))
    ) STORED;

CREATE INDEX titles_name_trgm_idx ON titles USING gin (name gin_trgm_ops);
CREATE INDEX titles_search_vector_idx ON titles USING gin (search_vector);
CREATE INDEX titles_release_year_idx ON titles (release_year);
CREATE INDEX titles_metadata_refresh_idx ON titles (metadata_updated_at NULLS FIRST)
    WHERE metadata_status <> 'complete';
/* [jooq ignore stop] */

CREATE TABLE movies (
    title_id        UUID PRIMARY KEY REFERENCES titles (id) ON DELETE CASCADE,
    runtime_minutes INTEGER,
    collection_id   UUID,
    CONSTRAINT movies_runtime_positive CHECK (runtime_minutes IS NULL OR runtime_minutes > 0)
);

CREATE TABLE series (
    title_id                UUID PRIMARY KEY REFERENCES titles (id) ON DELETE CASCADE,
    status                  VARCHAR(32),
    first_air_date          DATE,
    last_air_date           DATE,
    season_count            INTEGER,
    episode_count           INTEGER,
    average_episode_minutes INTEGER,
    -- Denormalised on purpose: commitment scoring and the optimiser's capacity
    -- constraint both need this on every candidate, and aggregating episodes at
    -- query time is the obvious latency regression (spec section 14.3).
    total_runtime_minutes   INTEGER
);

CREATE TABLE seasons (
    id              UUID    PRIMARY KEY,
    series_title_id UUID    NOT NULL REFERENCES series (title_id) ON DELETE CASCADE,
    season_number   INTEGER NOT NULL,
    name            VARCHAR(255),
    episode_count   INTEGER,
    air_date        DATE,
    CONSTRAINT seasons_unique UNIQUE (series_title_id, season_number),
    CONSTRAINT seasons_number_non_negative CHECK (season_number >= 0)
);

CREATE TABLE episodes (
    id              UUID    PRIMARY KEY,
    season_id       UUID    NOT NULL REFERENCES seasons (id) ON DELETE CASCADE,
    episode_number  INTEGER NOT NULL,
    name            VARCHAR(500),
    overview        TEXT,
    runtime_minutes INTEGER,
    air_date        DATE,
    external_id     VARCHAR(64),
    CONSTRAINT episodes_unique UNIQUE (season_id, episode_number),
    CONSTRAINT episodes_number_positive CHECK (episode_number > 0)
);

CREATE TABLE genres (
    id   SMALLINT     PRIMARY KEY,
    name VARCHAR(64)  NOT NULL,
    CONSTRAINT genres_name_unique UNIQUE (name)
);

CREATE TABLE title_genres (
    title_id UUID          NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    genre_id SMALLINT      NOT NULL REFERENCES genres (id),
    weight   NUMERIC(4, 3) NOT NULL DEFAULT 1.000,
    PRIMARY KEY (title_id, genre_id)
);

CREATE INDEX title_genres_genre_idx ON title_genres (genre_id, title_id);

CREATE TABLE title_keywords (
    title_id UUID          NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    keyword  VARCHAR(128)  NOT NULL,
    source   VARCHAR(16)   NOT NULL DEFAULT 'tmdb',
    weight   NUMERIC(4, 3) NOT NULL DEFAULT 1.000,
    PRIMARY KEY (title_id, keyword)
);

CREATE TABLE people (
    id          UUID         PRIMARY KEY,
    external_id VARCHAR(64),
    name        VARCHAR(255) NOT NULL,
    profile_url TEXT,
    CONSTRAINT people_external_unique UNIQUE (external_id)
);

CREATE TABLE title_credits (
    title_id       UUID        NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    person_id      UUID        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    credit_type    VARCHAR(16) NOT NULL,
    character_name VARCHAR(255),
    department     VARCHAR(64),
    billing_order  INTEGER,
    PRIMARY KEY (title_id, person_id, credit_type)
);

CREATE INDEX title_credits_person_idx ON title_credits (person_id);

-- Semantic similarity powers candidate generation, diversification (MMR) and
-- preference matching. A REAL[] with in-application cosine similarity is enough
-- at this catalogue size; pgvector is the upgrade path (spec section 14.3).
CREATE TABLE title_embeddings (
    title_id          UUID         PRIMARY KEY REFERENCES titles (id) ON DELETE CASCADE,
    embedding_model   VARCHAR(64)  NOT NULL,
    embedding_version INTEGER      NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

/* [jooq ignore start] */
ALTER TABLE title_embeddings ADD COLUMN embedding REAL[] NOT NULL;
/* [jooq ignore stop] */
