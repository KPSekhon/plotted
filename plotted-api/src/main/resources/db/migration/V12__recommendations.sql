-- Plotted :: V12 :: Queue Theory decision log
--
-- Spec sections 9.5 and 17. Every recommendation Plotted serves is recorded with
-- the context that produced it, the score of each item, and -- the part that
-- matters most -- the probability with which each item was selected.
--
-- WHY THE PROPENSITY COLUMN EXISTS BEFORE ANYTHING USES IT
--
-- Phase 7's evaluation harness estimates how a *new* ranker would have performed
-- using decisions the *current* ranker made. Every off-policy estimator (IPS,
-- SNIPS, doubly-robust) divides by the probability that the logged policy chose
-- the action it chose. Without that number the logs are unusable for evaluation
-- and no amount of later work recovers it: the policy that produced them is gone.
--
-- It is one numeric column. Adding it now costs nothing; adding it in phase 7
-- costs every night of data collected before then.

CREATE TABLE recommendation_requests (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    requested_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    region_code       CHAR(2)      NOT NULL,
    -- The context asked for. Nullable because "I have no particular deadline" is
    -- a real request, and storing a sentinel would make it indistinguishable
    -- from someone with exactly that many minutes.
    available_minutes INTEGER,
    access_policy     VARCHAR(32)  NOT NULL,
    -- How many candidates existed, and how many survived the hard filters. The
    -- pair is what makes "nothing fit" diagnosable months later.
    candidate_count   INTEGER      NOT NULL,
    eligible_count    INTEGER      NOT NULL,
    outcome           VARCHAR(16)  NOT NULL,
    -- Which filter removed how many, when nothing survived. A diagnostic rather
    -- than an error: the request was answered, the answer was "nothing".
    rejection_summary JSONB,
    -- Which ranker produced this. Phase 7 compares rankers, and rows from two
    -- different scoring functions must never be pooled by accident.
    ranker_version    VARCHAR(32)  NOT NULL,
    latency_ms        INTEGER,
    CONSTRAINT recommendation_requests_outcome_check CHECK (
        outcome IN ('served', 'nothing_fit')
    ),
    CONSTRAINT recommendation_requests_counts_sane CHECK (
        candidate_count >= 0 AND eligible_count >= 0 AND eligible_count <= candidate_count
    )
);

-- Postgres does not index foreign keys automatically, and CI asserts that every
-- one is covered by a non-partial index.
CREATE INDEX recommendation_requests_user_idx ON recommendation_requests (user_id);
CREATE INDEX recommendation_requests_requested_at_idx ON recommendation_requests (requested_at);

CREATE TABLE recommendation_items (
    id                    UUID          PRIMARY KEY,
    request_id            UUID          NOT NULL REFERENCES recommendation_requests (id) ON DELETE CASCADE,
    title_id              UUID          NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    -- 1 is the pick, 2 and 3 the backups.
    position              SMALLINT      NOT NULL,
    score                 NUMERIC(6, 5) NOT NULL,
    -- Whether this slot was filled by exploration rather than by the score.
    exploration           BOOLEAN       NOT NULL DEFAULT FALSE,
    -- The probability this policy assigned to choosing this title for this
    -- position. Strictly positive: an action recorded as impossible but taken
    -- makes every importance-weighted estimate divide by zero, so a policy that
    -- cannot produce a positive propensity must not log the row at all.
    propensity            NUMERIC(8, 7) NOT NULL,
    -- What the score was made of, feature by feature, after renormalisation.
    -- The explanations shown to the user are rendered from this and nothing
    -- else, which is what stops them from becoming plausible prose.
    feature_contributions JSONB         NOT NULL,
    CONSTRAINT recommendation_items_unique_position UNIQUE (request_id, position),
    CONSTRAINT recommendation_items_position_range CHECK (position BETWEEN 1 AND 10),
    CONSTRAINT recommendation_items_score_range CHECK (score BETWEEN 0 AND 1),
    CONSTRAINT recommendation_items_propensity_range CHECK (propensity > 0 AND propensity <= 1)
);

CREATE INDEX recommendation_items_request_idx ON recommendation_items (request_id);
CREATE INDEX recommendation_items_title_idx ON recommendation_items (title_id);

COMMENT ON COLUMN recommendation_items.propensity IS
    'P(this title chosen for this position) under the policy that served it. Required by phase 7 off-policy evaluation; cannot be reconstructed after the fact.';

COMMENT ON COLUMN recommendation_requests.rejection_summary IS
    'Counts per hard filter when nothing survived. Explains the empty answer instead of silently relaxing a constraint.';
