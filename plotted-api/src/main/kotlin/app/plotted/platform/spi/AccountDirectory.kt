package app.plotted.platform.spi

import java.util.UUID

/**
 * Whether the account an access token names still exists.
 *
 * Access tokens are stateless and short-lived, which means a token outlives the
 * account it was issued for by up to its remaining TTL. That is normally a
 * theoretical window; in Plotted it is a routine one, because the demo sweep
 * deletes expired demo accounts every hour and a visitor's open tab keeps
 * presenting a signed token that verifies perfectly against an account that is
 * gone.
 *
 * What happened without this check was not a clean refusal. Each endpoint
 * discovered the missing account in its own way and answered differently —
 * `/alerts` returned an empty list, `/users/me/settings` a 404, `/pilot/profile`
 * a 204, and `/watchlist` a **500** from a foreign-key violation, because
 * provisioning the default list inserts a row keyed on a user id that no longer
 * has a row to point at. One deleted account, four answers, and the loudest of
 * them told the client to report a server fault.
 *
 * Deliberately narrow: a boolean, keyed on an id the caller has already
 * verified a signature over. It is not a login, it does not return the account,
 * and nothing downstream can be tempted to read personal data off the security
 * context — the same rule [SessionIssuer] follows from the other direction.
 */
interface AccountDirectory {
    fun exists(userId: UUID): Boolean
}
