package app.plotted.identity.domain

import app.plotted.identity.persistence.RefreshTokenRepository
import app.plotted.platform.config.PlottedProperties
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Rotating refresh tokens with reuse detection.
 *
 * Tokens are opaque random bytes, stored only as a SHA-256 digest. Each refresh
 * spends the presented token and issues a successor in the same family. Because
 * a spent token can never be spent twice, presenting one means a copy is in
 * someone else's hands -- so the whole family is revoked rather than just that
 * token, which logs out the attacker and the legitimate session together. That
 * is the correct trade: a surprise logout is recoverable, a silently shared
 * session is not.
 */
@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    properties: PlottedProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = properties.security.jwt.refreshTokenTtl
    private val random = SecureRandom()

    /** Starts a new family. Used at registration and at password login. */
    fun issueNewFamily(userId: UUID, context: ClientContext): String = issue(userId, UUID.randomUUID(), context)

    /**
     * Spends [presentedToken] and returns its successor.
     *
     * @throws ApiException with [ErrorCode.TOKEN_INVALID] for an unknown, expired,
     *   revoked or already-spent token. The caller cannot tell which, deliberately.
     */
    @Transactional
    fun rotate(presentedToken: String, context: ClientContext): Rotation {
        val stored = repository.findByHash(hash(presentedToken))
            ?: throw ApiException(ErrorCode.TOKEN_INVALID, "Refresh token is not valid")

        if (stored.usedAt != null) {
            // Reuse. Everything descended from this family is now suspect.
            val revoked = repository.revokeFamily(stored.familyId, "reuse_detected")
            log.warn(
                "Refresh token reuse detected for user {}; revoked {} tokens in family {}",
                stored.userId,
                revoked,
                stored.familyId,
            )
            throw ApiException(ErrorCode.TOKEN_INVALID, "Refresh token is not valid")
        }

        if (stored.revokedAt != null || stored.expiresAt.isBefore(Instant.now(clock))) {
            throw ApiException(ErrorCode.TOKEN_INVALID, "Refresh token is not valid")
        }

        if (!repository.markUsed(stored.id)) {
            // Lost a race with a concurrent refresh: treat exactly as reuse.
            repository.revokeFamily(stored.familyId, "reuse_detected")
            throw ApiException(ErrorCode.TOKEN_INVALID, "Refresh token is not valid")
        }

        return Rotation(
            userId = stored.userId,
            refreshToken = issue(stored.userId, stored.familyId, context),
        )
    }

    /** Ends the session the token belongs to. Unknown tokens are a silent no-op. */
    @Transactional
    fun revoke(presentedToken: String) {
        repository.findByHash(hash(presentedToken))?.let {
            repository.revokeFamily(it.familyId, "logout")
        }
    }

    private fun issue(userId: UUID, familyId: UUID, context: ClientContext): String {
        val raw = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        repository.insert(
            id = UUID.randomUUID(),
            userId = userId,
            familyId = familyId,
            tokenHash = hash(token),
            expiresAt = Instant.now(clock).plus(ttl),
            userAgentHash = context.userAgent?.let(::hash),
            ipHash = context.ipAddress?.let(::hash),
        )
        return token
    }

    /**
     * SHA-256, not a password hash. The input is 256 bits of entropy from a CSPRNG,
     * so there is nothing to brute-force and a slow KDF would only add latency to
     * every refresh.
     */
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    data class Rotation(
        val userId: UUID,
        val refreshToken: String,
    )

    /**
     * Coarse client fingerprint, stored hashed. Enough to investigate a suspected
     * theft; not enough to reconstruct someone's browsing (spec section 18).
     */
    data class ClientContext(
        val userAgent: String?,
        val ipAddress: String?,
    )

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
