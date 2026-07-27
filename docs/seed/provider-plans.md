# Seeding provider plan pricing

`V8__reference_data.sql` seeds the Canadian providers but deliberately seeds **no
prices**.

Plan pricing is manually curated from public pricing pages. A migration has no
way to verify what a service costs today, and seeding invented numbers would put
fabricated money in front of a user and into the subscription optimiser's
objective function. Wrong pricing does not produce a visibly broken feature — it
produces confident, wrong financial advice, which is worse.

So the prices are entered by hand, once, from the pricing pages, with the date
they were checked.

## How to enter them

For each provider you actually subscribe to, open its Canadian pricing page and
record the plan name, billing period and price. Then insert a row per plan.

`validity` is a half-open `DATERANGE`: `[checked-on, )` with an unbounded upper
bound means "current". When a price changes, close the old row and insert a new
one — the GiST exclusion constraint will reject any overlap, which is the point.

```sql
INSERT INTO provider_plans (
    id, provider_id, region_code, name, billing_period,
    price, currency, ad_supported, simultaneous_streams, validity
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM providers WHERE slug = 'netflix'),
    'CA',
    'Standard with ads',
    'monthly',
    0.00,               -- the price from the pricing page
    'CAD',
    TRUE,
    2,
    daterange(DATE '2026-07-26', NULL)   -- the date you checked
);
```

Closing a price period when it changes:

```sql
UPDATE provider_plans
   SET validity = daterange(lower(validity), DATE '2026-09-01')
 WHERE provider_id = (SELECT id FROM providers WHERE slug = 'netflix')
   AND name = 'Standard with ads'
   AND upper_inf(validity);
```

Save your inserts to `docs/seed/provider-plans.local.sql`. That path is
git-ignored: prices are point-in-time facts about a market, and a stale committed
price is worse than no committed price.

## Providers seeded in V8

| Slug | Type |
|---|---|
| `netflix`, `prime-video`, `disney-plus`, `crave`, `apple-tv-plus`, `paramount-plus` | subscription |
| `tubi`, `cbc-gem` | free |
| `apple-tv-store`, `google-play` | transactional |
| `hoopla`, `kanopy` | library |

Hoopla and Kanopy are library services, reachable free with a Canadian library
card. They fit the platform-neutral premise better than a disc price lookup, and
Side Quest treats them as first-class access options.
