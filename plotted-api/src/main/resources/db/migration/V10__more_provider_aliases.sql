-- Plotted :: V10 :: Closing the provider mapping gap
--
-- V9 mapped the majors and the Canadian broadcasters, which left a documented
-- gap: roughly seventy of TMDB's ~135 Canadian providers were mapped, and
-- anything unmapped has its availability discarded. That shows up as a title
-- Plotted believes nobody carries -- the hardest kind of wrong to notice.
--
-- This closes the part of the tail that actually appears: speciality streamers,
-- anime and world cinema services, and the brands that reach Canada only
-- through Amazon or Apple channels. Identifiers again come from TMDB's live
-- /watch/providers list for CA, not from guesswork.
--
-- Still deliberately not exhaustive. What remains is the very long tail of
-- single-genre services, and ProviderResolver reports anything unmapped rather
-- than swallowing it, so the next gap announces itself.

/* [jooq ignore start] */

INSERT INTO providers (id, name, slug, provider_type, website_url, active) VALUES
    -- Speciality subscriptions available directly.
    ('8f4d0a10-2f6d-4a58-9c6e-00000000002e', 'ARROW',            'arrow',            'subscription', 'https://www.arrow-player.com/',    TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000002f', 'AsianCrush',       'asiancrush',       'subscription', 'https://www.asiancrush.com/',      TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000030', 'BroadwayHD',       'broadwayhd',       'subscription', 'https://www.broadwayhd.com/',      TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000031', 'Cineverse',        'cineverse',        'subscription', 'https://www.cineverse.com/',       TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000032', 'Citytv+',          'citytv-plus',      'subscription', 'https://www.citytv.com/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000033', 'Fandor',           'fandor',           'subscription', 'https://www.fandor.com/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000034', 'HIDIVE',           'hidive',           'subscription', 'https://www.hidive.com/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000035', 'Hoichoi',          'hoichoi',          'subscription', 'https://www.hoichoi.tv/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000036', 'iQIYI',            'iqiyi',            'subscription', 'https://www.iq.com/',              TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000037', 'KOCOWA',           'kocowa',           'subscription', 'https://www.kocowa.com/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000038', 'MagellanTV',       'magellan-tv',      'subscription', 'https://www.magellantv.com/',      TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000039', 'MHz Choice',       'mhz-choice',       'subscription', 'https://www.mhzchoice.com/',       TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000003a', 'Midnight Pulp',    'midnight-pulp',    'subscription', 'https://www.midnightpulp.com/',    TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000003b', 'OVID',             'ovid',             'subscription', 'https://www.ovid.tv/',             TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000003c', 'Rakuten Viki',     'rakuten-viki',     'subscription', 'https://www.viki.com/',            TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000003d', 'RetroCrush',       'retrocrush',       'free',         'https://www.retrocrush.tv/',       TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000003e', 'Shahid VIP',       'shahid-vip',       'subscription', 'https://shahid.mbc.net/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000003f', 'Sun Nxt',          'sun-nxt',          'subscription', 'https://www.sunnxt.com/',          TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000040', 'WOW Presents Plus','wow-presents-plus','subscription', 'https://www.wowpresentsplus.com/', TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000041', 'Film Movement Plus','film-movement-plus','subscription','https://www.filmmovementplus.com/', TRUE),

    -- Reach Canadian viewers only through reseller channels, so these have
    -- canonical rows but no `direct` alias below.
    ('8f4d0a10-2f6d-4a58-9c6e-000000000042', 'IFC Films Unlimited', 'ifc-films-unlimited', 'subscription', 'https://www.ifcfilms.com/',  TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000043', 'MGM+',             'mgm-plus',         'subscription', 'https://www.mgmplus.com/',         TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000044', 'Starz',            'starz',            'subscription', 'https://www.starz.com/',           TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000045', 'Lionsgate+',       'lionsgate-plus',   'subscription', 'https://www.lionsgateplus.com/',   TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000046', 'PBS Masterpiece',  'pbs-masterpiece',  'subscription', 'https://www.pbs.org/masterpiece/', TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000047', 'PBS Documentaries','pbs-documentaries','subscription', 'https://www.pbs.org/',             TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000048', 'BBC Earth',        'bbc-earth',        'subscription', 'https://www.bbcearth.com/',        TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-000000000049', 'BBC Select',       'bbc-select',       'subscription', 'https://www.bbcselect.com/',       TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000004a', 'TELETOON+',        'teletoon-plus',    'subscription', 'https://www.teletoon.com/',        TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000004b', 'Stingray',         'stingray',         'subscription', 'https://www.stingray.com/',        TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000004c', 'Love Nature',      'love-nature',      'subscription', 'https://www.lovenature.com/',      TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000004d', 'Smithsonian Channel', 'smithsonian-channel', 'subscription', 'https://www.smithsonianchannel.com/', TRUE),
    ('8f4d0a10-2f6d-4a58-9c6e-00000000004e', 'Sony Pictures Core', 'sony-pictures-core', 'subscription', 'https://www.sonypicturescore.com/', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO provider_aliases (tmdb_provider_id, provider_id, alias_name, alias_kind) VALUES
    (529,  '8f4d0a10-2f6d-4a58-9c6e-00000000002e', 'ARROW',                          'direct'),
    (514,  '8f4d0a10-2f6d-4a58-9c6e-00000000002f', 'AsianCrush',                     'direct'),
    (554,  '8f4d0a10-2f6d-4a58-9c6e-000000000030', 'BroadwayHD',                     'direct'),
    (1957, '8f4d0a10-2f6d-4a58-9c6e-000000000031', 'Cineverse',                      'direct'),
    (1985, '8f4d0a10-2f6d-4a58-9c6e-000000000032', 'Citytvplus',                     'direct'),
    (2171, '8f4d0a10-2f6d-4a58-9c6e-000000000032', 'Citytvplus Amazon Channel',      'reseller_channel'),
    (25,   '8f4d0a10-2f6d-4a58-9c6e-000000000033', 'Fandor',                         'direct'),
    (430,  '8f4d0a10-2f6d-4a58-9c6e-000000000034', 'HiDive',                         'direct'),
    (2390, '8f4d0a10-2f6d-4a58-9c6e-000000000034', 'Hidive Amazon Channel',          'reseller_channel'),
    (315,  '8f4d0a10-2f6d-4a58-9c6e-000000000035', 'Hoichoi',                        'direct'),
    (581,  '8f4d0a10-2f6d-4a58-9c6e-000000000036', 'iQIYI',                          'direct'),
    (464,  '8f4d0a10-2f6d-4a58-9c6e-000000000037', 'Kocowa',                         'direct'),
    (551,  '8f4d0a10-2f6d-4a58-9c6e-000000000038', 'Magellan TV',                    'direct'),
    (427,  '8f4d0a10-2f6d-4a58-9c6e-000000000039', 'Mhz Choice',                     'direct'),
    (1960, '8f4d0a10-2f6d-4a58-9c6e-00000000003a', 'Midnight Pulp',                  'direct'),
    (433,  '8f4d0a10-2f6d-4a58-9c6e-00000000003b', 'OVID',                           'direct'),
    (344,  '8f4d0a10-2f6d-4a58-9c6e-00000000003c', 'Rakuten Viki',                   'direct'),
    (446,  '8f4d0a10-2f6d-4a58-9c6e-00000000003d', 'Retrocrush',                     'direct'),
    (1715, '8f4d0a10-2f6d-4a58-9c6e-00000000003e', 'Shahid VIP',                     'direct'),
    (309,  '8f4d0a10-2f6d-4a58-9c6e-00000000003f', 'Sun Nxt',                        'direct'),
    (546,  '8f4d0a10-2f6d-4a58-9c6e-000000000040', 'WOW Presents Plus',              'direct'),
    (579,  '8f4d0a10-2f6d-4a58-9c6e-000000000041', 'Film Movement Plus',             'direct'),
    (2395, '8f4d0a10-2f6d-4a58-9c6e-000000000041', 'Film Movement Plus Amazon Channel', 'reseller_channel'),

    -- Reseller-only in Canada.
    (587,  '8f4d0a10-2f6d-4a58-9c6e-000000000042', 'IFC Amazon Channel',             'reseller_channel'),
    (2056, '8f4d0a10-2f6d-4a58-9c6e-000000000042', 'IFC Films Unlimited Apple TV Channel', 'reseller_channel'),
    (588,  '8f4d0a10-2f6d-4a58-9c6e-000000000043', 'MGM Amazon Channel',             'reseller_channel'),
    (1794, '8f4d0a10-2f6d-4a58-9c6e-000000000044', 'Starz Amazon Channel',           'reseller_channel'),
    (2358, '8f4d0a10-2f6d-4a58-9c6e-000000000045', 'Lionsgate+ Amazon Channels',     'reseller_channel'),
    (294,  '8f4d0a10-2f6d-4a58-9c6e-000000000046', 'PBS Masterpiece Amazon Channel', 'reseller_channel'),
    (2430, '8f4d0a10-2f6d-4a58-9c6e-000000000047', 'PBS Documentaries Amazon Channel', 'reseller_channel'),
    (610,  '8f4d0a10-2f6d-4a58-9c6e-000000000048', 'BBC Earth Amazon Channel',       'reseller_channel'),
    (2039, '8f4d0a10-2f6d-4a58-9c6e-000000000049', 'BBC Select Apple Tv channel',    'reseller_channel'),
    (589,  '8f4d0a10-2f6d-4a58-9c6e-00000000004a', 'TELETOON+ Amazon Channel',       'reseller_channel'),
    (2158, '8f4d0a10-2f6d-4a58-9c6e-00000000004b', 'Stingray Amazon Channel',        'reseller_channel'),
    (608,  '8f4d0a10-2f6d-4a58-9c6e-00000000004c', 'Love Nature Amazon Channel',     'reseller_channel'),
    (2052, '8f4d0a10-2f6d-4a58-9c6e-00000000004c', 'Love Nature Apple TV Channel',   'reseller_channel'),
    (609,  '8f4d0a10-2f6d-4a58-9c6e-00000000004d', 'Smithsonian Channel Amazon Channel', 'reseller_channel'),
    (2745, '8f4d0a10-2f6d-4a58-9c6e-00000000004e', 'Sony Pictures Core Amazon Channel', 'reseller_channel')
ON CONFLICT (tmdb_provider_id) DO NOTHING;

/* [jooq ignore stop] */
