package app.plotted.identity.domain

import app.plotted.identity.persistence.UserRepository
import app.plotted.platform.error.NotFoundException
import app.plotted.platform.security.AuthenticatedUser
import app.plotted.platform.security.JwtService
import app.plotted.platform.spi.SessionIssuer
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity's side of the [SessionIssuer] contract.
 *
 * The account is looked up rather than trusted from the caller, so a module
 * asking for a session for an id that does not exist gets a 404 instead of a
 * signed token for a user who is not there. That matters more than it looks:
 * the access token carries the email as a claim, and a caller supplying its own
 * would be choosing what the rest of the system believes about who is calling.
 */
@Component
class SessionIssuerAdapter(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenService,
    private val jwtService: JwtService,
) : SessionIssuer {
    override fun issueFor(userId: UUID, client: SessionIssuer.ClientContext): SessionIssuer.Session {
        val account = users.findById(userId) ?: throw NotFoundException("Account")
        val access = jwtService.issueAccessToken(AuthenticatedUser(account.id, account.email))
        val refresh = refreshTokens.issueNewFamily(
            account.id,
            RefreshTokenService.ClientContext(userAgent = client.userAgent, ipAddress = client.ipAddress),
        )
        return SessionIssuer.Session(
            accessToken = access.token,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = refresh,
        )
    }
}
