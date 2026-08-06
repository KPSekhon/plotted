-- Plotted :: V11 :: Canadian provider plan prices
--
-- Spec section 7.1. `docs/seed/provider-plans.md` explains why V4 shipped these
-- unseeded: a migration cannot verify what a service costs, and an invented
-- price does not produce a visibly broken feature, it produces confident, wrong
-- financial advice.
--
-- These are not invented. Every figure below was read from a published source on
-- 2026-08-06 and is recorded with the date it was checked, which is what the
-- `validity` range is for. That still makes them *researched*, not *verified* --
-- see the warning at the end of this file.
--
-- WHERE EACH NUMBER CAME FROM
--
--   Apple TV+      apple.com/ca/apple-tv-plus       (the provider's own page)
--   CBC Gem        cbchelp.cbc.ca                    (the provider's own help centre)
--   Disney+        Canadian press coverage of the November 2025 increase
--                  (mobilesyrup, CP24, Daily Hive -- three outlets agreeing)
--   Netflix        Canadian pricing guides, several agreeing
--   Crave          Canadian pricing guides, several agreeing
--   Amazon Prime   Canadian pricing guides, several agreeing
--   Paramount+     Canadian pricing guides -- LOWEST CONFIDENCE, see below
--
-- The two figures taken from a provider's own page are the two that a secondary
-- source got wrong: Apple TV+ was widely reported at $12.99, which is the US
-- price. The Canadian page says $14.99. Treat that as the general lesson.
--
-- NAMING AND THE EXCLUSION CONSTRAINT
--
-- `provider_plans_no_overlap` excludes on (provider, region, name, validity), so
-- two rows for one provider cannot share a name and an overlapping date range.
-- A monthly and an annual price for the same tier therefore need distinct names,
-- which is why the annual rows say so in their name rather than relying on
-- `billing_period` to disambiguate them.
--
-- `simultaneous_streams` is left NULL wherever it was not stated plainly. NULL
-- means unknown; a guess would be indistinguishable from a fact once stored.

/* [jooq ignore start] */
-- Fenced because `validity` is added by a fenced ALTER in V4 and the jOOQ
-- generator never sees that column. An INSERT naming it would fail codegen.

INSERT INTO provider_plans
    (id, provider_id, region_code, name, billing_period, price, currency, ad_supported, simultaneous_streams, validity)
VALUES
    -- Netflix -------------------------------------------------------------
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'netflix'), 'CA',
     'Standard with ads', 'monthly', 7.99, 'CAD', TRUE, 2, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'netflix'), 'CA',
     'Standard', 'monthly', 18.99, 'CAD', FALSE, 2, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'netflix'), 'CA',
     'Premium', 'monthly', 23.99, 'CAD', FALSE, 4, daterange(DATE '2026-08-06', NULL)),

    -- Crave ---------------------------------------------------------------
    -- The Basic tier was withdrawn in October 2025; two tiers remain.
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'crave'), 'CA',
     'Standard with ads', 'monthly', 11.99, 'CAD', TRUE, NULL, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'crave'), 'CA',
     'Standard with ads, billed yearly', 'annual', 119.99, 'CAD', TRUE, NULL, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'crave'), 'CA',
     'Premium', 'monthly', 22.00, 'CAD', FALSE, 4, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'crave'), 'CA',
     'Premium, billed yearly', 'annual', 220.00, 'CAD', FALSE, 4, daterange(DATE '2026-08-06', NULL)),

    -- Disney+ -------------------------------------------------------------
    -- Prices as of the increase that took effect 2025-11-04. The gap between
    -- Standard and Premium really is only $1: Standard rose 12.99 -> 15.99 and
    -- Premium 15.99 -> 16.99 in the same change.
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'disney-plus'), 'CA',
     'Standard with ads', 'monthly', 8.99, 'CAD', TRUE, 2, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'disney-plus'), 'CA',
     'Standard', 'monthly', 15.99, 'CAD', FALSE, 2, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'disney-plus'), 'CA',
     'Premium', 'monthly', 16.99, 'CAD', FALSE, 4, daterange(DATE '2026-08-06', NULL)),

    -- Amazon Prime Video --------------------------------------------------
    -- Prime Video is not sold separately in Canada; it comes with a Prime
    -- membership, and that membership price is what someone actually pays. Ads
    -- are the default, with an ad-free upgrade sold on top.
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'prime-video'), 'CA',
     'Prime membership', 'monthly', 9.99, 'CAD', TRUE, NULL, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'prime-video'), 'CA',
     'Prime membership, billed yearly', 'annual', 99.00, 'CAD', TRUE, NULL, daterange(DATE '2026-08-06', NULL)),

    -- Apple TV+ -----------------------------------------------------------
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'apple-tv-plus'), 'CA',
     'Apple TV+', 'monthly', 14.99, 'CAD', FALSE, NULL, daterange(DATE '2026-08-06', NULL)),

    -- Paramount+ ----------------------------------------------------------
    -- Lowest confidence of the set: the sources are secondary, and Paramount+
    -- was reported to be changing its tiers during 2026. Check these first.
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'paramount-plus'), 'CA',
     'Basic with ads', 'monthly', 6.99, 'CAD', TRUE, NULL, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'paramount-plus'), 'CA',
     'Standard', 'monthly', 10.99, 'CAD', FALSE, NULL, daterange(DATE '2026-08-06', NULL)),
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'paramount-plus'), 'CA',
     'Premium', 'monthly', 13.99, 'CAD', FALSE, NULL, daterange(DATE '2026-08-06', NULL)),

    -- CBC Gem -------------------------------------------------------------
    -- The free tier is genuinely free and needs no row; Gem Premium is the paid
    -- one. This is why `FREE` providers stay in the subscribable list.
    (gen_random_uuid(), (SELECT id FROM providers WHERE slug = 'cbc-gem'), 'CA',
     'Gem Premium', 'monthly', 5.99, 'CAD', FALSE, NULL, daterange(DATE '2026-08-06', NULL));

/* [jooq ignore stop] */

-- DELIBERATELY NOT SEEDED
--
-- Tubi, Google Play Movies, Hoopla and Kanopy. Tubi is free and ad-supported
-- with nothing to subscribe to; Google Play is transactional, so it has rental
-- prices per title rather than a plan; Hoopla and Kanopy are reached through a
-- library card, which the library pays for and the provider does not bill.
--
-- WHAT THIS DATA IS, AND WHAT IT IS NOT
--
-- It is a real, sourced starting point so that coverage and the phase 5
-- optimiser have something to run against. It is not a substitute for the
-- per-user prices captured by the subscriptions screen, which remain the figures
-- Plotted actually reasons with: `user_subscriptions.actual_price` overrides
-- this on every read, because a grandfathered rate or a bundle discount is what
-- someone is really billed.
--
-- Streaming prices move several times a year, and some of these were read from
-- secondary sources rather than the provider. Before any of this is used to give
-- a person financial advice, check it against the provider's own page and close
-- the stale row rather than editing it:
--
--     UPDATE provider_plans
--        SET validity = daterange(lower(validity), DATE 'the-day-it-changed')
--      WHERE provider_id = (SELECT id FROM providers WHERE slug = '...')
--        AND name = '...'
--        AND upper_inf(validity);
--
-- then insert the new price with `validity` opening on the day you checked. The
-- exclusion constraint enforces that the history stays non-overlapping.
