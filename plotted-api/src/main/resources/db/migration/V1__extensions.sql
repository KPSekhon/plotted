-- Plotted :: V1 :: PostgreSQL extensions
--
-- Everything in this file is fenced off from the jOOQ code generator. jOOQ reads
-- these migration scripts directly (see plotted-api/build.gradle.kts and
-- docs/adr/0004-jooq-over-jpa.md) and its SQL parser has no model for extension
-- management. Postgres treats the fence markers as ordinary comments.

/* [jooq ignore start] */

-- Case-insensitive text, used for e-mail addresses.
CREATE EXTENSION IF NOT EXISTS citext;

-- Trigram matching, used for catalogue search and for fuzzy title matching
-- during viewing-history import.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GiST support for scalar types, which is what makes the temporal exclusion
-- constraints on provider_plans, title_availability and
-- subscription_billing_periods possible.
CREATE EXTENSION IF NOT EXISTS btree_gist;

/* [jooq ignore stop] */
