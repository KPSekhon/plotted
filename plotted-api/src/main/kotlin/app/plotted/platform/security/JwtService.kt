package app.plotted.platform.security

import app.plotted.platform.config.PlottedProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Issues and verifies short-lived access tokens.
 *
 * Access tokens are stateless and deliberately short-lived; the long-lived half
 * of the pair is the opaque, rotating refresh token held in Postgres, which is
 * what makes revocation and reuse detection possible at all.
 */
@Component
class JwtService(
    properties: PlottedProperties,
    private val clock: Clock,
) {
    private val config = properties.security.jwt
    private val key: SecretKey = Keys.hmacShaKeyFor(decodeSecret(config.secret))

    fun issueAccessToken(user: AuthenticatedUser): IssuedToken {
        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(config.accessTokenTtl)
        val token = Jwts.builder()
            .issuer(config.issuer)
            .subject(user.userId.toString())
            .claim("email", user.email)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
        return IssuedToken(token, expiresAt, config.accessTokenTtl.seconds)
    }

    /**
     * Returns the caller if the token verifies, or null if it does not. Callers
     * cannot tell an expired token from a forged one, which is intentional.
     */
    fun verify(token: String): AuthenticatedUser? = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(config.issuer)
            .clock { Date.from(Instant.now(clock)) }
            .build()
            .parseSignedClaims(token)
            .payload
        val email = claims["email"] as? String
        if (email == null) null else AuthenticatedUser(UUID.fromString(claims.subject), email)
    } catch (_: ExpiredJwtException) {
        null
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    data class IssuedToken(
        val token: String,
        val expiresAt: Instant,
        val expiresInSeconds: Long,
    )

    private companion object {
        /**
         * Accepts a base64-encoded key, falling back to raw UTF-8 bytes so a
         * developer-supplied passphrase still works. Either way HS256 needs at
         * least 256 bits of key material and we refuse to start without it.
         */
        fun decodeSecret(secret: String): ByteArray {
            val bytes =
                try {
                    Base64.getDecoder().decode(secret)
                } catch (_: IllegalArgumentException) {
                    secret.toByteArray(Charsets.UTF_8)
                }
            require(bytes.size >= 32) {
                "plotted.security.jwt.secret must decode to at least 32 bytes (got ${bytes.size})"
            }
            return bytes
        }
    }
}
