-- Plotted :: V8 :: Reference data
--
-- Genre identifiers are TMDB's own, so ingested titles map straight through
-- without a translation table. Providers are the Canadian set the product
-- targets at launch (spec section 1.2: Canada only, deliberately).
--
-- Plan pricing is deliberately NOT seeded here. Prices are manually curated from
-- public pricing pages (spec section 7.1) and this file has no way to verify
-- them, so seeding invented numbers would put fabricated money in front of a
-- user. Fill provider_plans from docs/seed/provider-plans.md before running the
-- coverage or optimiser features.
--
-- Whole file fenced: the jOOQ generator models DDL, not data.

/* [jooq ignore start] */

INSERT INTO genres (id, name) VALUES
    (28,    'Action'),
    (12,    'Adventure'),
    (16,    'Animation'),
    (35,    'Comedy'),
    (80,    'Crime'),
    (99,    'Documentary'),
    (18,    'Drama'),
    (10751, 'Family'),
    (14,    'Fantasy'),
    (36,    'History'),
    (27,    'Horror'),
    (10402, 'Music'),
    (9648,  'Mystery'),
    (10749, 'Romance'),
    (878,   'Science Fiction'),
    (10770, 'TV Movie'),
    (53,    'Thriller'),
    (10752, 'War'),
    (37,    'Western'),
    (10759, 'Action & Adventure'),
    (10762, 'Kids'),
    (10763, 'News'),
    (10764, 'Reality'),
    (10765, 'Sci-Fi & Fantasy'),
    (10766, 'Soap'),
    (10767, 'Talk'),
    (10768, 'War & Politics')
ON CONFLICT (id) DO NOTHING;

INSERT INTO providers (id, name, slug, provider_type, website_url, active) VALUES
    ('8f4d0a10-2f6d-4a58-9c6e-000000000001', 'Netflix',           'netflix',       'subscription',   'https://www.netflix.com/ca/',       TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000002', 'Amazon Prime Video','prime-video',   'subscription',   'https://www.primevideo.com/',        TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000003', 'Disney+',           'disney-plus',   'subscription',   'https://www.disneyplus.com/en-ca',   TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000004', 'Crave',             'crave',         'subscription',   'https://www.crave.ca/',              TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000005', 'Apple TV+',         'apple-tv-plus', 'subscription',   'https://tv.apple.com/ca',            TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000006', 'Paramount+',        'paramount-plus','subscription',   'https://www.paramountplus.com/ca/',  TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000007', 'Tubi',              'tubi',          'free',           'https://tubitv.com/',                TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000008', 'CBC Gem',           'cbc-gem',       'free',           'https://gem.cbc.ca/',                TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000009', 'Apple TV (Store)',  'apple-tv-store','transactional',  'https://tv.apple.com/ca',            TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000000a', 'Google Play Movies','google-play',   'transactional',  'https://play.google.com/store/movies', TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000000b', 'Hoopla',            'hoopla',        'library',        'https://www.hoopladigital.com/',     TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000000c', 'Kanopy',            'kanopy',        'library',        'https://www.kanopy.com/',            TRUE)
ON CONFLICT (id) DO NOTHING;

/* [jooq ignore stop] */
