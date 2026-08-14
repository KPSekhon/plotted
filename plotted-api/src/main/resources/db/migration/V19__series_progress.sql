-- Where the user is in a series.
--
-- Tonight can say "Chainsaw Man, about 24 minutes an episode" and then leave the
-- user to open another app and work out which episode they are actually on. That
-- is precisely the decision Plotted exists to remove, so the answer has to be
-- "S1 E8", and this is the one row it takes.
--
-- POSITION, NOT PACE
--
-- This records where somebody is, and nothing about how fast they got there. One
-- last-completed episode is a *position*; pace needs completion events over time,
-- which is a table of its own and is deliberately not this one. Anything built on
-- top of this may say "at your configured 3 hours a week you would finish before
-- 19 August" and must never say "at your current pace" -- the second is a claim
-- about behaviour nobody has measured.
--
-- WHY THE POSITION RATHER THAN AN EPISODE ID
--
-- The obvious column is `last_completed_episode_id UUID REFERENCES episodes`.
-- Episode ids are in fact stable -- `SeasonRepository.upsert` conflicts on
-- (season_id, episode_number) and updates in place -- so that would work.
--
-- The position is better for the question actually being asked. "What is next"
-- is `ORDER BY (season_number, episode_number)` over rows *after* this one, which
-- needs the numbers rather than the identity; an id would have to be joined back
-- to its own numbers first, every time. And a position stays meaningful when the
-- catalogue changes underneath it: if an episode is ever removed upstream, a
-- foreign key either deletes the user's place or leaves it dangling, whereas a
-- position still orders correctly against whatever episodes remain.
--
-- The cost is that the database cannot check the episode exists, so
-- `SeriesProgressService` checks it on write and refuses a position the catalogue
-- has never heard of.

CREATE TABLE user_series_progress (
    user_id                      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    series_title_id              UUID        NOT NULL REFERENCES series (title_id) ON DELETE CASCADE,

    -- The last episode finished, as season and episode number. Both NOT NULL:
    -- a row means "I have completed something", and the absence of a row means
    -- "not started". Making these nullable would add a third state that says the
    -- same thing as no row at all.
    last_completed_season_number  INTEGER    NOT NULL,
    last_completed_episode_number INTEGER    NOT NULL,

    -- How Plotted came to believe this. Only 'user' today; an import from a
    -- provider's history would be a different level of trust, and a column added
    -- later cannot say which of the existing rows were which.
    source                        TEXT       NOT NULL DEFAULT 'user',

    updated_at                    TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (user_id, series_title_id),

    CONSTRAINT user_series_progress_source_known
        CHECK (source IN ('user', 'import')),

    -- Season 0 is where TMDB files specials, and `recalculateTotalRuntime`
    -- already excludes it from the runtime a series is judged by. Somebody's
    -- place in the story is not a Christmas special, and allowing one here would
    -- make "next episode" step out of the main run and back again.
    CONSTRAINT user_series_progress_not_a_special
        CHECK (last_completed_season_number > 0),

    CONSTRAINT user_series_progress_episode_positive
        CHECK (last_completed_episode_number > 0)
);

COMMENT ON TABLE user_series_progress IS
    'The last episode the user finished, per series. Position only -- pace needs completion events over time and is deliberately not stored here.';

-- The primary key is (user_id, series_title_id), which covers lookups by user
-- because user_id leads it -- and covers nothing at all by series.
--
-- That matters for the direction nobody queries in: `ON DELETE CASCADE` on
-- series_title_id means removing a title makes Postgres find every progress row
-- pointing at it, and with no index on that column it sequentially scans the
-- whole table to do it. Rare, and it gets slower exactly as the table grows.
--
-- CI asserts this rather than trusting anyone to remember: the migrations job
-- fails on any foreign key without a covering non-partial index, which is how
-- this omission was found rather than shipped.
CREATE INDEX user_series_progress_series_idx ON user_series_progress (series_title_id);
