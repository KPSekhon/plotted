# Privacy

Plotted asks for a detailed record of what you watch. That record is unusually
revealing — it exposes taste, mood, schedule, and sometimes health, relationships
or beliefs. This page states plainly what the product does with it.

These are commitments the code is built to keep, not a policy written after the
fact.

## What Plotted collects

- Account details: email address, display name, region, time zone
- Your watchlist, priorities and blocked titles
- Your subscriptions, their prices and renewal dates
- Viewing progress and events, either entered by you or imported from a provider
  data export you supply
- Recommendation sessions: what was suggested, what you chose, and why it ranked
  the way it did

## What Plotted does not do

- **No third-party analytics.** No advertising identifiers, no cross-site
  trackers, no session recording.
- **No selling or sharing.** Viewing data is not sold, shared or used to build
  audience segments.
- **No scraping.** Plotted does not scrape provider websites or circumvent any
  provider's terms of service.
- **No financial actions.** Plotted can recommend cancelling or pausing a
  service. It never performs the action, never holds a payment method, and never
  asks for one.

## Household privacy

Sharing a household should not mean sharing a viewing history.

- A member's individual ratings and private watchlist are never visible to other
  members without explicit opt-in. `household_members.share_viewing_history`
  defaults to `false`.
- Household aggregates are suppressed below a minimum member count. In a
  two-person household, "the household watched X" is not an aggregate — it is an
  attribution.
- Guests in a Group Plot session get a temporary profile that expires. They are
  not silently converted into tracked users.

## Retention

- Raw `viewing_events` are kept for a bounded window and then rolled up into
  aggregates. The detail needed to reconstruct a timeline does not need to live
  forever.
- Imported viewing history is deleted when the source account is disconnected.
- Availability snapshots are retained indefinitely. They describe catalogues, not
  people, and contain no personal data.

## Your data

- **Export.** `POST /api/v1/users/me/export` produces everything held about you.
- **Deletion.** `DELETE /api/v1/users/me` runs a workflow that revokes external
  tokens, deletes personal identifiers, removes sessions and household
  memberships, anonymises recommendation and viewing events, and writes a
  deletion audit record.

Both endpoints arrive with phase 12. Until then this is a single-user development
system, and the honest statement is that account deletion is a `DELETE` against
your own local database.

## Security

- Passwords are hashed with Argon2id.
- Access tokens are short-lived and held in memory only, never in browser
  storage.
- Refresh tokens are stored as digests, rotate on every use, and a replayed token
  revokes the whole session family.
- External provider tokens are envelope-encrypted at rest with a rotatable key
  identifier.
- Sensitive actions are written to an append-only audit log. IP addresses are
  hashed, never stored in the clear.

## Contact

Plotted is a personal project. Raise anything on the issue tracker.
