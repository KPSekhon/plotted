-- Pilot Season: the answers a taste profile is fitted from.
--
-- The maths, the ladder and the profile arrived in phase 9 with nowhere to put
-- an answer. This is that place.
--
-- One row per question the user was *shown*, answered or skipped. A skipped row
-- is kept rather than discarded: it records that the question was asked and
-- declined, which is how the ladder knows not to offer it again. It must never
-- reach the fitter, and the constraints below are what make that structural
-- rather than a rule somebody has to remember.

CREATE TABLE pilot_comparisons (
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Which axis the ladder chose this pair to isolate. Recorded so a fit can be
    -- audited after the fact: "why does this profile think you like comedies"
    -- should be answerable from the rows, not by rerunning the ladder against a
    -- catalogue that has since changed.
    axis            VARCHAR(16) NOT NULL,

    left_title_id   UUID        NOT NULL REFERENCES titles (id) ON DELETE CASCADE,
    right_title_id  UUID        NOT NULL REFERENCES titles (id) ON DELETE CASCADE,

    -- Null means skipped. See the all-or-nothing constraint below.
    chosen_title_id UUID        REFERENCES titles (id) ON DELETE CASCADE,

    -- The evidence, frozen: chosen attributes minus rejected, as it was computed
    -- when the person answered.
    --
    -- Stored rather than recomputed at fit time, for two reasons. The RECENCY
    -- axis is measured against the current year, so re-deriving an old answer
    -- would silently reinterpret it -- the person compared two titles as they
    -- were then, and that is the fact worth keeping. And a title later removed
    -- from the catalogue would otherwise take its evidence with it, which is a
    -- deletion in one module quietly changing a conclusion in another.
    attribute_difference JSONB,

    answered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A choice has to be one of the two things offered. Without this, a bug that
    -- posted an unrelated title id would be recorded as a preference and fitted
    -- as evidence, and nothing downstream could tell.
    CONSTRAINT pilot_comparisons_choice_was_offered CHECK (
        chosen_title_id IS NULL
        OR chosen_title_id = left_title_id
        OR chosen_title_id = right_title_id
    ),

    -- A pair of one title teaches nothing and would contribute a zero-difference
    -- row to the fit, which is not neutral: it is an observation asserting
    -- indifference the user never expressed.
    CONSTRAINT pilot_comparisons_pair_is_two_titles CHECK (left_title_id <> right_title_id),

    -- Skipping is all or nothing. A row with a choice and no difference cannot
    -- be fitted; a row with a difference and no choice is a difference computed
    -- from nothing. Both are unrepresentable rather than merely unlikely.
    CONSTRAINT pilot_comparisons_skip_is_all_or_nothing CHECK (
        (chosen_title_id IS NULL AND attribute_difference IS NULL)
        OR (chosen_title_id IS NOT NULL AND attribute_difference IS NOT NULL)
    ),

    -- Six axes, and the length is written down rather than inferred. If a
    -- seventh axis is ever added, this constraint fails on the next write and
    -- forces a migration that decides what to do about the rows already stored.
    -- The alternative -- accepting any array -- would silently feed the fitter
    -- vectors of the wrong length, or worse, of the right length and the wrong
    -- meaning.
    CONSTRAINT pilot_comparisons_difference_is_six_axes CHECK (
        attribute_difference IS NULL
        OR (
            jsonb_typeof(attribute_difference) = 'array'
            AND jsonb_array_length(attribute_difference) = 6
        )
    )
);

COMMENT ON COLUMN pilot_comparisons.chosen_title_id IS
    'The title picked, or null when the question was skipped. A skipped row is evidence of nothing and must be excluded from the fit.';

COMMENT ON COLUMN pilot_comparisons.attribute_difference IS
    'Chosen attributes minus rejected, on the six taste axes, frozen at answer time.';

CREATE INDEX pilot_comparisons_user_idx ON pilot_comparisons (user_id, answered_at);

-- One per title foreign key, for the cascade path.
--
-- Postgres does not index foreign keys automatically, and deleting a title has
-- to find every row referencing it on each of these three columns. The unique
-- pair index below does not help: it is built on LEAST/GREATEST expressions
-- rather than on the columns themselves, so a planner looking for
-- `left_title_id = ?` cannot use it.
CREATE INDEX pilot_comparisons_left_title_idx ON pilot_comparisons (left_title_id);
CREATE INDEX pilot_comparisons_right_title_idx ON pilot_comparisons (right_title_id);
CREATE INDEX pilot_comparisons_chosen_title_idx ON pilot_comparisons (chosen_title_id);

/* [jooq ignore start] */
-- One answer per pair per user, regardless of which side each title was shown
-- on. LEAST/GREATEST normalises the pair so that answering (A, B) also settles
-- (B, A) -- otherwise a rebuilt ladder that happened to swap the sides would ask
-- the same question again, and the second answer would be counted as
-- independent evidence when it is the same person answering the same question.
CREATE UNIQUE INDEX pilot_comparisons_pair_idx ON pilot_comparisons (
    user_id,
    LEAST(left_title_id, right_title_id),
    GREATEST(left_title_id, right_title_id)
);
/* [jooq ignore stop] */
