package app.plotted.platform.spi

import java.time.Instant
import java.util.UUID

/**
 * How a module that is not `identity` can put someone in a signed-in state.
 *
 * Exactly one caller today — demo mode, which has to hand a visitor a working
 * session without a password. It is an interface in the shared kernel rather
 * than a direct call for the reason set out in ADR 0008: `demo` reaching into
 * `identity.domain` would be the first feature-to-feature dependency in the
 * codebase, and the ArchUnit rule that forbids it is doing its job.
 *
 * Deliberately narrow. There is no "issue a session for this email", because
 * that is an authentication decision and it belongs behind the login path where
 * the timing-safe comparison and the audit entry live. This takes a user id that
 * the caller has already established it is entitled to, and does the token work
 * only.
 */
interface SessionIssuer {
    fun issueFor(userId: UUID, client: ClientContext): Session

    data class ClientContext(
        val userAgent: String?,
        val ipAddress: String?,
    )

    data class Session(
        val accessToken: String,
        val accessTokenExpiresAt: Instant,
        /** Raw refresh token. The caller sets the cookie; this does not know about HTTP. */
        val refreshToken: String,
    )
}
