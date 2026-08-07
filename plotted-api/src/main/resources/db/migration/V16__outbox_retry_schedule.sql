-- Backoff for the outbox relay.
--
-- V7 gave the outbox `attempts` and `last_error` but nothing to say *when* to try
-- again, so a relay could only retry every failing event on every tick. For an
-- event failing because a downstream service is down, that is a retry storm
-- aimed at something already struggling.

ALTER TABLE outbox ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

COMMENT ON COLUMN outbox.next_attempt_at IS
    'Earliest time the relay may claim this row again. Set to now() on insert so a new event is picked up immediately, and pushed out on each failure.';

/* [jooq ignore start] */
-- Replaces the V7 index. The relay claims on "unpublished and due", so the
-- predicate has to match the query or the index is read past every tick.
DROP INDEX IF EXISTS outbox_unpublished_idx;

CREATE INDEX outbox_claimable_idx ON outbox (next_attempt_at, id)
    WHERE published_at IS NULL;
/* [jooq ignore stop] */
