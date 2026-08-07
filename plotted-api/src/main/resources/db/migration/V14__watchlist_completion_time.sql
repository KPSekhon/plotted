-- When a watchlist item was finished.
--
-- `status` has always been able to reach 'completed', which is the closest label
-- this schema has to "the recommendation worked". What was missing is *when* it
-- got there, and every evaluation in phase 7 needs that: a temporal split cannot
-- divide outcomes it cannot date, and joining a decision logged in
-- recommendation_items to an outcome with no timestamp cannot tell whether the
-- outcome came before the decision or after it.
--
-- One column and a write. It is here early, ahead of the evaluation work that
-- consumes it, because the column starts collecting the moment it ships and no
-- later migration can recover a transition that happened before it existed.

ALTER TABLE watchlist_items ADD COLUMN completed_at TIMESTAMPTZ;

COMMENT ON COLUMN watchlist_items.completed_at IS
    'When status last transitioned into completed; null otherwise. Null on a completed row means the transition predates this column -- unknown, not zero.';

-- A row that is not completed must not carry a completion time.
--
-- Only this direction is enforced. The converse -- every completed row has a
-- timestamp -- cannot be, because rows completed before this migration have no
-- knowable value and inventing one (added_at, or now()) would put a fabricated
-- date into the evaluation harness's temporal split, which is precisely the
-- input that must not be guessed. A completed row with a null completed_at means
-- "finished at an unknown time", and the harness excludes it for the same reason
-- coverage excludes never-checked titles from its denominator rather than
-- scoring them zero.
--
-- The direction that IS enforced is the one that goes wrong silently: an item
-- moved back to pending while keeping the timestamp it earned would read to
-- every later query as a completion that is still outstanding.
ALTER TABLE watchlist_items
    ADD CONSTRAINT watchlist_items_completion_time_requires_completion
    CHECK (completed_at IS NULL OR status = 'completed');

/* [jooq ignore start] */
-- Partial: the temporal split reads only rows that have a completion time, and
-- on any real list those are the minority. Indexing the nulls would be paying
-- for the rows the query is defined to skip.
CREATE INDEX watchlist_items_completed_at_idx ON watchlist_items (completed_at)
    WHERE completed_at IS NOT NULL;
/* [jooq ignore stop] */
