-- Where a recommended title came from.
--
-- Today Plotted only ever orders what is already on the watchlist, so this
-- column has two values and one of them is nearly everything. It exists now
-- rather than later for the same reason `propensity` did: it is a fact about a
-- decision at the moment the decision was made, and it cannot be reconstructed
-- afterwards. When discovery starts proposing titles the user never chose, the
-- question that matters is whether those recommendations are accepted and
-- finished at the same rate as the ones they did choose -- and that question can
-- only be answered against decisions logged before anyone thought to ask it.
--
-- Three values, and the third is the one this is really for:
--
--   watchlist    Explicitly on the list. The user told us they wanted it.
--   continuing   A series they have already started, resolved to the next
--                episode. Behaviourally different from picking something new,
--                and separated because "carry on" and "start something" are not
--                the same recommendation even when they rank identically.
--   discovery    Proposed by Plotted from the wider catalogue. Nothing produces
--                this yet; see ADR 0009.

-- Added nullable, backfilled, then made NOT NULL -- rather than NOT NULL in one
-- statement, which is what the first version of this migration did.
--
-- That version asserted in a comment that the table was empty everywhere, which
-- was written without checking and was wrong within seconds: the development
-- database had 69 rows from ordinary testing and the migration failed outright
-- with 23502.
--
-- The part worth keeping is *why CI would not have caught it*. The migrations
-- job applies every file to a **clean** database, where a NOT NULL column with
-- no default is always satisfiable because there are no rows to violate it. So
-- this class of migration passes CI unconditionally and fails the first time it
-- meets a populated database -- which, on the current trajectory, would have
-- been the production one, after the first users. A local database with real
-- rows in it caught what a green pipeline could not.
ALTER TABLE recommendation_items ADD COLUMN candidate_source TEXT;

-- Not a guess. Every row that exists was served from the watchlist, because
-- that is the only source the recommender has ever had -- discovery does not
-- exist yet and `continuing` was introduced by the same change as this column.
-- Backfilling them as anything else, or leaving them null, would put a claim in
-- the log that nothing measured.
UPDATE recommendation_items SET candidate_source = 'watchlist' WHERE candidate_source IS NULL;

ALTER TABLE recommendation_items ALTER COLUMN candidate_source SET NOT NULL;

-- No DEFAULT, deliberately, now that the existing rows are handled. A default
-- would let a future writer omit the source and have it silently recorded as
-- watchlist, which is precisely the measurement error this column exists to
-- prevent: discovered picks attributed to a source they never had.
ALTER TABLE recommendation_items
    ADD CONSTRAINT recommendation_items_candidate_source_known
        CHECK (candidate_source IN ('watchlist', 'continuing', 'discovery'));

COMMENT ON COLUMN recommendation_items.candidate_source IS
    'Where this candidate came from: watchlist (explicitly listed), continuing (a series already started), discovery (proposed from the wider catalogue). Recorded per served item so acceptance and completion can be compared per source.';

/* [jooq ignore start] */
-- Partial, because the interesting query is "how did discovered picks perform",
-- and the watchlist rows are the overwhelming majority that query never wants.
CREATE INDEX recommendation_items_discovery_idx
    ON recommendation_items (candidate_source)
    WHERE candidate_source <> 'watchlist';
/* [jooq ignore stop] */
