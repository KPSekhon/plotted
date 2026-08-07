-- Demo accounts: a way to see Plotted working without signing up.
--
-- Each visitor gets their **own** account rather than sharing one. A shared
-- demo account means the last visitor's watchlist edits are the next visitor's
-- starting state, so the first thing someone evaluating this project sees is
-- somebody else's half-finished experiment. Cheap to create, and they expire.
--
-- The alternative -- a read-only demo -- was rejected because the two headline
-- features are about *changing* your mind: reprioritising a watchlist and
-- watching the plan change is the demo.

ALTER TABLE users ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;

-- When this account stops being reachable. Null for real accounts, which do not
-- expire. Deliberately not a "delete after N days" job parameter: the expiry
-- belongs to the row, so a change to the retention window cannot retroactively
-- extend the life of accounts already handed out.
ALTER TABLE users ADD COLUMN expires_at TIMESTAMPTZ;

-- Only demo accounts may expire. Without this, a bug that set expires_at on a
-- real account would delete a paying user's data, and nothing would object.
ALTER TABLE users
    ADD CONSTRAINT users_only_demo_accounts_expire CHECK (expires_at IS NULL OR is_demo);

-- A demo account with no expiry is a leak: it would never be collected and the
-- table would grow without bound. Both directions are stated because only
-- stating one is how you end up with the other.
ALTER TABLE users
    ADD CONSTRAINT users_demo_accounts_must_expire CHECK (NOT is_demo OR expires_at IS NOT NULL);

/* [jooq ignore start] */
-- Partial: real accounts are the overwhelming majority and none of them are
-- ever swept, so indexing them would be paying for rows the query never wants.
CREATE INDEX users_demo_expiry_idx ON users (expires_at) WHERE is_demo;
/* [jooq ignore stop] */
