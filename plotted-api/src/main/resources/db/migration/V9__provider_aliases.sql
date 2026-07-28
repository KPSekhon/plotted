-- Plotted :: V9 :: Provider canonicalisation
--
-- Why this table exists.
--
-- TMDB returns billing variants and reseller channels as distinct providers.
-- A single title can come back as:
--
--     Crave, Crave Amazon Channel
--     Netflix, Netflix Standard with Ads, Netflix Kids
--     Paramount Plus, Paramount+ Amazon Channel, Paramount Plus Apple TV Channel,
--     Paramount Plus Basic with Ads, Paramount Plus Premium
--
-- Those are one catalogue bought several ways, not several services. Stored
-- naively they would inflate watchlist coverage and let Cancel Culture
-- "cover" a title by recommending a subscription that does not independently
-- exist. Coverage is the optimiser's primary input, so this is a correctness
-- problem in the money-facing feature, not a cosmetic one.
--
-- Every identifier below was read from TMDB's own /watch/providers list for
-- region CA rather than guessed. Regenerate with the provider audit described in
-- docs/seed/provider-plans.md when TMDB adds services.

CREATE TABLE provider_aliases (
    -- TMDB provider ids are global rather than per-region, so this is the key.
    tmdb_provider_id INTEGER      PRIMARY KEY,
    provider_id      UUID         NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    -- The name TMDB uses, kept so a mapping can be audited against the upstream
    -- list without a network call.
    alias_name       VARCHAR(160) NOT NULL,
    alias_kind       VARCHAR(24)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT provider_aliases_kind_check CHECK (
        alias_kind IN ('direct', 'reseller_channel', 'ad_tier', 'plan_tier', 'profile')
    )
);

CREATE INDEX provider_aliases_provider_idx ON provider_aliases (provider_id);

COMMENT ON TABLE provider_aliases IS 'Maps a TMDB provider id onto the Plotted provider a user can actually subscribe to. Many-to-one: reseller channels and billing tiers collapse onto one provider.';

COMMENT ON COLUMN provider_aliases.alias_kind IS 'direct = the service itself; reseller_channel = bought through Amazon or Apple; ad_tier and plan_tier = billing variants; profile = a filtered view such as Netflix Kids.';

/* [jooq ignore start] */

-- Providers beyond the launch twelve seeded in V8. Canadian broadcasters are
-- included because free, legal, regional catalogue is exactly the sort of option
-- a platform-neutral tool should surface and a platform-owned one never will.
INSERT INTO providers (id, name, slug, provider_type, website_url, active) VALUES
    ('8f4d0a10-2f6d-4a58-9c6e-00000000000d', 'Amazon Video',        'amazon-video',      'transactional', 'https://www.amazon.ca/',                 TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000000e', 'YouTube',             'youtube',           'transactional', 'https://www.youtube.com/',               TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000000f', 'YouTube Premium',     'youtube-premium',   'subscription',  'https://www.youtube.com/premium',        TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000010', 'Pluto TV',            'pluto-tv',          'free',          'https://pluto.tv/ca',                    TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000011', 'CTV',                 'ctv',               'free',          'https://www.ctv.ca/',                    TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000012', 'Global TV',           'global-tv',         'free',          'https://www.globaltv.com/',              TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000013', 'Noovo',               'noovo',             'free',          'https://www.noovo.ca/',                  TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000014', 'ICI Tou.tv',          'ici-toutv',         'free',          'https://ici.tou.tv/',                    TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000015', 'TVO',                 'tvo',               'free',          'https://www.tvo.org/',                   TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000016', 'Knowledge Network',   'knowledge-network', 'free',          'https://www.knowledge.ca/',              TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000017', 'NFB',                 'nfb',               'free',          'https://www.nfb.ca/',                    TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000018', 'Telequebec',          'telequebec',        'free',          'https://video.telequebec.tv/',           TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000019', 'TV5Unis',             'tv5unis',           'free',          'https://www.tv5unis.ca/',                TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000001a', 'MUBI',                'mubi',              'subscription',  'https://mubi.com/',                      TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000001b', 'AMC+',                'amc-plus',          'subscription',  'https://www.amcplus.com/',               TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000001c', 'The Criterion Channel', 'criterion-channel', 'subscription', 'https://www.criterionchannel.com/',     TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000001d', 'Shudder',             'shudder',           'subscription',  'https://www.shudder.com/',               TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000001e', 'BritBox',             'britbox',           'subscription',  'https://www.britbox.com/ca/',            TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000001f', 'Acorn TV',            'acorn-tv',          'subscription',  'https://acorn.tv/',                      TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000020', 'Crunchyroll',         'crunchyroll',       'subscription',  'https://www.crunchyroll.com/',           TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000021', 'Hollywood Suite',     'hollywood-suite',   'subscription',  'https://hollywoodsuite.ca/',             TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000022', 'Club Illico',         'club-illico',       'subscription',  'https://www.clubillico.tv/',             TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000023', 'Plex',                'plex',              'free',          'https://watch.plex.tv/',                 TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000024', 'hayu',                'hayu',              'subscription',  'https://www.hayu.com/',                  TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000025', 'Sundance Now',        'sundance-now',      'subscription',  'https://www.sundancenow.com/',           TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000026', 'CuriosityStream',     'curiosity-stream',  'subscription',  'https://curiositystream.com/',           TRUE),
    -- OUTtv, StackTV and Super Channel reach Canadian viewers only through
    -- reseller channels, so they have canonical rows here but no `direct` alias.
    ('8f4d0a10-2f6d-4a58-9c6e-000000000027', 'OUTtv',               'outtv',             'subscription',  'https://www.outtv.ca/',                  TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000028', 'StackTV',             'stacktv',           'subscription',  'https://www.stacktv.ca/',                TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000029', 'Super Channel',       'super-channel',     'subscription',  'https://www.superchannel.ca/',           TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000002a', 'Discovery+',          'discovery-plus',    'subscription',  'https://www.discoveryplus.com/ca',       TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000002b', 'fuboTV',              'fubotv',            'subscription',  'https://www.fubo.tv/ca',                 TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000002c', 'CosmoGo',             'cosmogo',           'transactional', 'https://www.cosmogo.ca/',                TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000002d', 'FlixFling',           'flixfling',         'transactional', 'https://www.flixfling.com/',             TRUE)
ON CONFLICT (id) DO NOTHING;

-- The mapping itself.
INSERT INTO provider_aliases (tmdb_provider_id, provider_id, alias_name, alias_kind) VALUES
    -- Netflix: one catalogue, three ways of being listed.
    (8,    '8f4d0a10-2f6d-4a58-9c6e-000000000001', 'Netflix',                            'direct'),
    (1796, '8f4d0a10-2f6d-4a58-9c6e-000000000001', 'Netflix Standard with Ads',          'ad_tier'),
    (175,  '8f4d0a10-2f6d-4a58-9c6e-000000000001', 'Netflix Kids',                       'profile'),

    (119,  '8f4d0a10-2f6d-4a58-9c6e-000000000002', 'Amazon Prime Video',                 'direct'),
    (2100, '8f4d0a10-2f6d-4a58-9c6e-000000000002', 'Amazon Prime Video with Ads',        'ad_tier'),
    (10,   '8f4d0a10-2f6d-4a58-9c6e-00000000000d', 'Amazon Video',                       'direct'),

    (337,  '8f4d0a10-2f6d-4a58-9c6e-000000000003', 'Disney Plus',                        'direct'),

    -- Crave, the case that prompted this table.
    (230,  '8f4d0a10-2f6d-4a58-9c6e-000000000004', 'Crave',                              'direct'),
    (2604, '8f4d0a10-2f6d-4a58-9c6e-000000000004', 'Crave Amazon Channel',               'reseller_channel'),

    (350,  '8f4d0a10-2f6d-4a58-9c6e-000000000005', 'Apple TV',                           'direct'),
    (2243, '8f4d0a10-2f6d-4a58-9c6e-000000000005', 'Apple TV Amazon Channel',            'reseller_channel'),
    (2,    '8f4d0a10-2f6d-4a58-9c6e-000000000009', 'Apple TV Store',                     'direct'),

    (531,  '8f4d0a10-2f6d-4a58-9c6e-000000000006', 'Paramount Plus',                     'direct'),
    (582,  '8f4d0a10-2f6d-4a58-9c6e-000000000006', 'Paramount+ Amazon Channel',          'reseller_channel'),
    (1853, '8f4d0a10-2f6d-4a58-9c6e-000000000006', 'Paramount Plus Apple TV Channel',    'reseller_channel'),
    (2304, '8f4d0a10-2f6d-4a58-9c6e-000000000006', 'Paramount Plus Basic with Ads',      'ad_tier'),
    (2303, '8f4d0a10-2f6d-4a58-9c6e-000000000006', 'Paramount Plus Premium',             'plan_tier'),

    (73,   '8f4d0a10-2f6d-4a58-9c6e-000000000007', 'Tubi TV',                            'direct'),
    (314,  '8f4d0a10-2f6d-4a58-9c6e-000000000008', 'CBC Gem',                            'direct'),
    (3,    '8f4d0a10-2f6d-4a58-9c6e-00000000000a', 'Google Play Movies',                 'direct'),
    (212,  '8f4d0a10-2f6d-4a58-9c6e-00000000000b', 'Hoopla',                             'direct'),
    (192,  '8f4d0a10-2f6d-4a58-9c6e-00000000000e', 'YouTube',                            'direct'),
    (188,  '8f4d0a10-2f6d-4a58-9c6e-00000000000f', 'YouTube Premium',                    'direct'),

    -- Free and public broadcasters.
    (300,  '8f4d0a10-2f6d-4a58-9c6e-000000000010', 'Pluto TV',                           'direct'),
    (326,  '8f4d0a10-2f6d-4a58-9c6e-000000000011', 'CTV',                                'direct'),
    (449,  '8f4d0a10-2f6d-4a58-9c6e-000000000012', 'Global TV',                          'direct'),
    (516,  '8f4d0a10-2f6d-4a58-9c6e-000000000013', 'Noovo',                              'direct'),
    (146,  '8f4d0a10-2f6d-4a58-9c6e-000000000014', 'iciTouTV',                           'direct'),
    (488,  '8f4d0a10-2f6d-4a58-9c6e-000000000015', 'tvo',                                'direct'),
    (525,  '8f4d0a10-2f6d-4a58-9c6e-000000000016', 'Knowledge Network',                  'direct'),
    (441,  '8f4d0a10-2f6d-4a58-9c6e-000000000017', 'NFB',                                'direct'),
    (2691, '8f4d0a10-2f6d-4a58-9c6e-000000000018', 'Tele Quebec',                        'direct'),
    (2665, '8f4d0a10-2f6d-4a58-9c6e-000000000019', 'TV5 Unis',                           'direct'),
    (538,  '8f4d0a10-2f6d-4a58-9c6e-000000000023', 'Plex',                               'direct'),

    -- Speciality subscriptions, each with its reseller variants.
    (11,   '8f4d0a10-2f6d-4a58-9c6e-00000000001a', 'MUBI',                               'direct'),
    (526,  '8f4d0a10-2f6d-4a58-9c6e-00000000001b', 'AMC+',                               'direct'),
    (528,  '8f4d0a10-2f6d-4a58-9c6e-00000000001b', 'AMC+ Amazon Channel',                'reseller_channel'),
    (1854, '8f4d0a10-2f6d-4a58-9c6e-00000000001b', 'AMC Plus Apple TV Channel',          'reseller_channel'),
    (258,  '8f4d0a10-2f6d-4a58-9c6e-00000000001c', 'Criterion Channel',                  'direct'),
    (99,   '8f4d0a10-2f6d-4a58-9c6e-00000000001d', 'Shudder',                            'direct'),
    (204,  '8f4d0a10-2f6d-4a58-9c6e-00000000001d', 'Shudder Amazon Channel',             'reseller_channel'),
    (2049, '8f4d0a10-2f6d-4a58-9c6e-00000000001d', 'Shudder Apple TV Channel',           'reseller_channel'),
    (151,  '8f4d0a10-2f6d-4a58-9c6e-00000000001e', 'BritBox',                            'direct'),
    (197,  '8f4d0a10-2f6d-4a58-9c6e-00000000001e', 'BritBox Amazon Channel',             'reseller_channel'),
    (1852, '8f4d0a10-2f6d-4a58-9c6e-00000000001e', 'Britbox Apple TV Channel',           'reseller_channel'),
    (87,   '8f4d0a10-2f6d-4a58-9c6e-00000000001f', 'Acorn TV',                           'direct'),
    (196,  '8f4d0a10-2f6d-4a58-9c6e-00000000001f', 'AcornTV Amazon Channel',             'reseller_channel'),
    (2034, '8f4d0a10-2f6d-4a58-9c6e-00000000001f', 'Acorn TV Apple TV',                  'reseller_channel'),
    (283,  '8f4d0a10-2f6d-4a58-9c6e-000000000020', 'Crunchyroll',                        'direct'),
    (1968, '8f4d0a10-2f6d-4a58-9c6e-000000000020', 'Crunchyroll Amazon Channel',         'reseller_channel'),
    (182,  '8f4d0a10-2f6d-4a58-9c6e-000000000021', 'Hollywood Suite',                    'direct'),
    (705,  '8f4d0a10-2f6d-4a58-9c6e-000000000021', 'Hollywood Suite Amazon Channel',     'reseller_channel'),
    (469,  '8f4d0a10-2f6d-4a58-9c6e-000000000022', 'Club Illico',                        'direct'),
    (223,  '8f4d0a10-2f6d-4a58-9c6e-000000000024', 'Hayu',                               'direct'),
    (296,  '8f4d0a10-2f6d-4a58-9c6e-000000000024', 'Hayu Amazon Channel',                'reseller_channel'),
    (143,  '8f4d0a10-2f6d-4a58-9c6e-000000000025', 'Sundance Now',                       'direct'),
    (205,  '8f4d0a10-2f6d-4a58-9c6e-000000000025', 'Sundance Now Amazon Channel',        'reseller_channel'),
    (2048, '8f4d0a10-2f6d-4a58-9c6e-000000000025', 'Sundance Now Apple TV Channel',      'reseller_channel'),
    (190,  '8f4d0a10-2f6d-4a58-9c6e-000000000026', 'Curiosity Stream',                   'direct'),

    -- Reseller-only in Canada: no `direct` row exists upstream.
    (607,  '8f4d0a10-2f6d-4a58-9c6e-000000000027', 'OUTtv Amazon Channel',               'reseller_channel'),
    (2044, '8f4d0a10-2f6d-4a58-9c6e-000000000027', 'OUTtv Apple TV Channel',             'reseller_channel'),
    (606,  '8f4d0a10-2f6d-4a58-9c6e-000000000028', 'StackTV Amazon Channel',             'reseller_channel'),
    (2525, '8f4d0a10-2f6d-4a58-9c6e-000000000029', 'Super Channel Plus',                 'direct'),
    (605,  '8f4d0a10-2f6d-4a58-9c6e-000000000029', 'Super Channel Amazon Channel',       'reseller_channel'),

    (520,  '8f4d0a10-2f6d-4a58-9c6e-00000000002a', 'Discovery +',                        'direct'),
    (584,  '8f4d0a10-2f6d-4a58-9c6e-00000000002a', 'Discovery+ Amazon Channel',          'reseller_channel'),
    (257,  '8f4d0a10-2f6d-4a58-9c6e-00000000002b', 'fuboTV',                             'direct'),
    (140,  '8f4d0a10-2f6d-4a58-9c6e-00000000002c', 'CosmoGo',                            'direct'),
    (331,  '8f4d0a10-2f6d-4a58-9c6e-00000000002d', 'FlixFling',                          'direct')
ON CONFLICT (tmdb_provider_id) DO NOTHING;

/* [jooq ignore stop] */
