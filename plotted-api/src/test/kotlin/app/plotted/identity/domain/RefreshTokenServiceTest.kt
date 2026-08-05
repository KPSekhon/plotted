package app.plotted.identity.domain

import app.plotted.identity.persistence.RefreshTokenRepository
import app.plotted.platform.config.PlottedProperties
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * The behaviour under test is the security property, not the storage: a refresh
 * token may be spent exactly once, and spending one twice must take down the
 * whole family rather than just failing that one call.
 */
class RefreshTokenServiceTest {
    private val now = Instant.parse("2026-07-26T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = mockk<RefreshTokenRepository>(relaxed = true)
    private val service = RefreshTokenService(repository, properties(), clock)
    private val context = RefreshTokenService.ClientContext(userAgent = "test-agent", ipAddress = "203.0.113.7")

    private val userId = UUID.randomUUID()
    private val familyId = UUID.randomUUID()
    private val tokenId = UUID.randomUUID()

    @Test
    fun `issuing stores a hash rather than the token itself`() {
        val hashSlot = slot<String>()
        every {
            repository.insert(any(), any(), any(), capture(hashSlot), any(), any(), any())
        } returns Unit

        val token = service.issueNewFamily(userId, context)

        token shouldNotBe hashSlot.captured
        hashSlot.captured.length shouldBe 64 // SHA-256, hex encoded
        // The raw token must not be recoverable from what was stored.
        hashSlot.captured.contains(token) shouldBe false
    }

    @Test
    fun `rotation spends the presented token and issues a successor in the same family`() {
        val presented = service.issueNewFamily(userId, context)
        givenStoredToken(presented, usedAt = null, revokedAt = null)
        every { repository.markUsed(tokenId) } returns true

        val rotation = service.rotate(presented, context)

        rotation.userId shouldBe userId
        rotation.refreshToken shouldNotBe presented
        verify { repository.markUsed(tokenId) }
        verify {
            repository.insert(any(), userId, familyId, any(), any(), any(), any())
        }
    }

    @Test
    fun `reusing an already-spent token revokes the entire family`() {
        val presented = service.issueNewFamily(userId, context)
        givenStoredToken(presented, usedAt = now.minus(Duration.ofMinutes(5)), revokedAt = null)

        val error = shouldFailWith(ErrorCode.TOKEN_INVALID) { service.rotate(presented, context) }

        error.code shouldBe ErrorCode.TOKEN_INVALID
        // Only that the revocation is requested, and on its own transaction so
        // the rejection below it cannot roll it back. That it actually survives
        // is a property of the database, and is asserted by
        // AuthenticationFlowIntegrationTest against a real one -- a mock records
        // the call either way, which is precisely how the rollback bug lived
        // here undetected.
        verify { repository.revokeFamilyIndependently(familyId, "reuse_detected") }
    }

    @Test
    fun `losing the race to mark a token used is treated exactly as reuse`() {
        val presented = service.issueNewFamily(userId, context)
        givenStoredToken(presented, usedAt = null, revokedAt = null)
        every { repository.markUsed(tokenId) } returns false

        shouldFailWith(ErrorCode.TOKEN_INVALID) { service.rotate(presented, context) }

        verify { repository.revokeFamilyIndependently(familyId, "reuse_detected") }
    }

    @Test
    fun `an expired token is rejected without revoking the family`() {
        val presented = service.issueNewFamily(userId, context)
        givenStoredToken(
            presented,
            usedAt = null,
            revokedAt = null,
            expiresAt = now.minus(Duration.ofSeconds(1)),
        )

        shouldFailWith(ErrorCode.TOKEN_INVALID) { service.rotate(presented, context) }

        // Expiry is not evidence of theft, so neither revocation route may fire.
        verify(exactly = 0) { repository.revokeFamily(any(), any()) }
        verify(exactly = 0) { repository.revokeFamilyIndependently(any(), any()) }
    }

    @Test
    fun `an unknown token is rejected`() {
        every { repository.findByHash(any()) } returns null

        shouldFailWith(ErrorCode.TOKEN_INVALID) { service.rotate("made-up-token", context) }
    }

    @Test
    fun `logout revokes the family the token belongs to`() {
        val presented = service.issueNewFamily(userId, context)
        givenStoredToken(presented, usedAt = null, revokedAt = null)

        service.revoke(presented)

        verify { repository.revokeFamily(familyId, "logout") }
    }

    @Test
    fun `logout with an unknown token is a silent no-op`() {
        every { repository.findByHash(any()) } returns null

        service.revoke("made-up-token")

        verify(exactly = 0) { repository.revokeFamily(any(), any()) }
    }

    private fun givenStoredToken(
        rawToken: String,
        usedAt: Instant?,
        revokedAt: Instant?,
        expiresAt: Instant = now.plus(Duration.ofDays(30)),
    ) {
        every { repository.findByHash(sha256Hex(rawToken)) } returns
            RefreshTokenRepository.StoredRefreshToken(
                id = tokenId,
                userId = userId,
                familyId = familyId,
                expiresAt = expiresAt,
                usedAt = usedAt,
                revokedAt = revokedAt,
            )
    }

    private fun shouldFailWith(code: ErrorCode, block: () -> Unit): ApiException {
        val error = runCatching(block).exceptionOrNull()
        check(error is ApiException) { "Expected an ApiException but got $error" }
        error.code shouldBe code
        return error
    }

    private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun properties() = PlottedProperties(
        security = PlottedProperties.SecurityProperties(
            jwt = PlottedProperties.JwtProperties(
                secret = Base64.getEncoder().encodeToString(ByteArray(32)),
            ),
        ),
    )
}
