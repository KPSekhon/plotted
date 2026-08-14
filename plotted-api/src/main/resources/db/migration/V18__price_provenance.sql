-- Where a price came from, and therefore whether Cancel Culture may spend it.
--
-- V11 seeded a researched list price per plan and was explicit that these are
-- *researched, not verified*: every figure was read from a published source on
-- 2026-08-06, two of them from the provider's own page and the rest from
-- secondary coverage. That warning lived in a comment, which is the wrong place
-- for it, because nothing downstream could act on it.
--
-- What actually happened is worse than the warning suggests.
-- `SubscriptionRepository` reads a held subscription's price as
-- `COALESCE(user_subscriptions.actual_price, provider_plans.price)`, so a
-- subscription the user never priced silently adopts the researched figure and
-- arrives at the optimiser indistinguishable from one they confirmed. The
-- optimiser then minimises real money against it and the result is presented as
-- advice. That is precisely the failure V11's own comment says it is avoiding,
-- reintroduced one join later.
--
-- So provenance becomes a column, and the objective function gets a trust
-- boundary rather than a warning.
--
--   reference  Researched from a published source. Good enough to show, and to
--              pre-fill a form with. NOT good enough to optimise against: list
--              prices miss legacy rates, student pricing, bundles, promotional
--              rates, annual plans and family arrangements, and every one of
--              those makes the published number wrong for this particular
--              person in the direction that matters.
--
--   verified   Read from a live pricing source Plotted itself checked, with a
--              date. Nothing produces this yet -- there is no pricing ingestion
--              -- and the value exists so the eventual one has somewhere to land
--              rather than being bolted on later.
--
-- A price the *user* entered needs no value here: it lives in
-- `user_subscriptions.actual_price`, and its provenance is the fact that they
-- typed it. That is the best source available and it always wins.

ALTER TABLE provider_plans
    ADD COLUMN price_provenance TEXT NOT NULL DEFAULT 'reference';

-- Spelled out rather than left to the application. A provenance value nothing
-- recognises would fall through whatever branch the optimiser writes last, and
-- the failure mode of that branch is spending money the user never confirmed.
ALTER TABLE provider_plans
    ADD CONSTRAINT provider_plans_price_provenance_known
        CHECK (price_provenance IN ('reference', 'verified'));

-- One string literal rather than two adjacent ones. Implicit concatenation is
-- standard SQL and Postgres accepts it; jOOQ's DDL parser, which generates the
-- classes from these files rather than from a live database, does not -- and it
-- fails the whole build at codegen rather than at migration time.
COMMENT ON COLUMN provider_plans.price_provenance IS
    'reference = researched from a published source, may be displayed but never optimised against. verified = checked by Plotted against a live source. See docs/seed/provider-plans.md.';

/* [jooq ignore start] */
-- Every existing row is reference by default, which is what they are. Stated
-- rather than relied upon, because a later backfill that assumed otherwise would
-- promote researched prices into the objective without anybody deciding to.
UPDATE provider_plans SET price_provenance = 'reference';
/* [jooq ignore stop] */
