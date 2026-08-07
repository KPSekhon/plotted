-- Plotted :: deployment preflight
--
-- Run this against a freshly provisioned database BEFORE the first migration,
-- as the role the application will migrate with. It answers one question:
-- will V1__extensions.sql succeed, and will the constraints that depend on it
-- actually work?
--
--     psql "$PLOTTED_DB_URL" -v ON_ERROR_STOP=1 -f ops/deploy/preflight.sql
--
-- If you have no psql, paste it into the provider's SQL console. This file is
-- deliberately plain SQL with no psql meta-commands, so it runs unchanged in a
-- browser console. It is idempotent and creates nothing that outlives the
-- session except the extensions themselves, which is what the first migration
-- would have created anyway.
--
-- WHY THIS EXISTS, and why it does more than check three names.
--
-- Some managed Postgres providers restrict CREATE EXTENSION. Without
-- btree_gist the GiST exclusion constraints on provider_plans,
-- title_availability and subscription_billing_periods cannot be created --
-- and those are what make duplicate availability rows unrepresentable, which
-- is what keeps every coverage number the optimiser depends on honest.
--
-- Checking that the extension is *listed* is not the same as checking that an
-- exclusion constraint *fires*. This script does the second thing: it builds a
-- constraint of the shape the real schema uses and proves it rejects an
-- overlap. A guard nobody has ever seen fail is a guard nobody knows is there.

-- 1. Version. The schema is written against 16.
SELECT current_setting('server_version') AS server_version;

-- 2. The three extensions. Identical to V1__extensions.sql, so a failure here
--    is exactly the failure the migration would have hit, at a point where
--    nothing has been written yet.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

SELECT name AS extension,
       CASE WHEN installed_version IS NULL THEN 'MISSING' ELSE 'installed' END AS status,
       installed_version
  FROM pg_available_extensions
 WHERE name IN ('citext', 'pg_trgm', 'btree_gist')
 ORDER BY name;

-- 3. The part that matters: an exclusion constraint of the real shape, proven
--    to reject an overlap.
--
--    This mirrors title_availability -- a DATERANGE keyed by two scalar columns,
--    which is precisely the combination that needs btree_gist. The original
--    design keyed these on a nullable column, and in Postgres NULL != NULL, so
--    the constraint never fired for the commonest case. That bug passes every
--    unit test and produces plausible, wrong financial advice, which is why the
--    rehearsal asserts the rejection rather than assuming it.
DO $$
DECLARE
    rejected BOOLEAN := FALSE;
BEGIN
    CREATE TEMP TABLE plotted_preflight_availability (
        title_id    UUID      NOT NULL,
        provider_id UUID      NOT NULL,
        validity    DATERANGE NOT NULL,
        EXCLUDE USING GIST (
            title_id WITH =,
            provider_id WITH =,
            validity WITH &&
        )
    ) ON COMMIT DROP;

    INSERT INTO plotted_preflight_availability
    VALUES (
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        daterange('2026-01-01', '2026-06-01')
    );

    BEGIN
        -- Overlaps the row above on the same (title, provider). The constraint
        -- must refuse this.
        INSERT INTO plotted_preflight_availability
        VALUES (
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000002',
            daterange('2026-03-01', '2026-09-01')
        );
    EXCEPTION
        WHEN exclusion_violation THEN
            rejected := TRUE;
    END;

    IF NOT rejected THEN
        RAISE EXCEPTION
            'PREFLIGHT FAILED: the exclusion constraint accepted an overlapping range. '
            'btree_gist is not doing its job on this database. Do NOT deploy: duplicate '
            'availability rows would become representable and every coverage number the '
            'optimiser produces would be inflated, silently.';
    END IF;

    RAISE NOTICE 'Exclusion constraint rehearsal passed: an overlapping range was rejected.';
END $$;

-- 4. Whether this role can run the migrations at all.
SELECT current_user                                    AS migrating_as,
       pg_catalog.has_schema_privilege('public', 'CREATE') AS can_create_in_public,
       pg_catalog.has_database_privilege(current_database(), 'CREATE') AS can_create_in_database;

-- If every extension above reads 'installed' and no exception was raised, then
-- V1__extensions.sql and the exclusion constraints will work on this database.
SELECT 'preflight complete' AS result;
