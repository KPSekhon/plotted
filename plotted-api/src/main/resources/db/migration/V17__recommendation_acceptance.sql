-- When somebody acted on a recommendation.
--
-- The decision log has recorded what Plotted *said* since V12. What it has never
-- recorded is whether anyone agreed, and both of the two metrics that carry this
-- product's argument need that:
--
--   * decision latency -- how long between being shown three options and picking
--     one. The claim is that Plotted saves time, and this is the only number
--     that can support or refute it.
--   * accepted-and-completed rate -- of the things somebody accepted, how many
--     they actually watched. A recommender with a high acceptance rate and a low
--     completion rate is persuasive rather than correct.
--
-- Neither can be computed retrospectively. An acceptance not recorded tonight is
-- not recoverable, for the same reason the propensity column had to exist before
-- anything used it.

ALTER TABLE recommendation_items ADD COLUMN accepted_at TIMESTAMPTZ;

COMMENT ON COLUMN recommendation_items.accepted_at IS
    'When the user chose this pick out of the ones offered. Null means not chosen, which for a served request is the ordinary case: at most one of three is.';

/* [jooq ignore start] */
-- Partial: the analytics read only accepted rows, and on any real log those are
-- a minority of items. Indexing the nulls would be paying for the rows every one
-- of these queries is defined to skip.
CREATE INDEX recommendation_items_accepted_idx ON recommendation_items (accepted_at)
    WHERE accepted_at IS NOT NULL;
/* [jooq ignore stop] */
