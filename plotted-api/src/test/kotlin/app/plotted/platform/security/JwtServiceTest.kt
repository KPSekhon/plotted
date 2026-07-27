package app.plotted.platform.security

import app.plotted.platform.config.PlottedProperties
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class JwtServiceTest {
    private val issuedAt = Instant.parse("2026-07-26T18:00:00Z")
    private val user = AuthenticatedUser(UUID.fromString("11111111-2222-3333-4444-555555555555"), "kanwar@example.com")

    private fun serviceAt(instant: Instant, secret: String = SECRET) =
        JwtService(propertiesWith(secret), Clock.fixed(instant, ZoneOffset.UTC))

    @Test
    fun `issues a token that verifies back to the same user`() {
        val service = serviceAt(issuedAt)

        val issued = service.issueAccessToken(user)

        service.verify(issued.token) shouldBe user
        issued.expiresAt shouldBe issuedAt.plus(Duration.ofMinutes(15))
        issued.expiresInSeconds shouldBe 900
    }

    @Test
    fun `rejects a token once it has expired`() {
        val issued = serviceAt(issuedAt).issueAccessToken(user)

        // One second past the fifteen-minute lifetime.
        val later = serviceAt(issuedAt.plus(Duration.ofMinutes(15)).plusSeconds(1))

        later.verify(issued.token).shouldBeNull()
    }

    @Test
    fun `still accepts a token one second before expiry`() {
        val issued = serviceAt(issuedAt).issueAccessToken(user)

        val justBefore = serviceAt(issuedAt.plus(Duration.ofMinutes(15)).minusSeconds(1))

        justBefore.verify(issued.token) shouldBe user
    }

    @Test
    fun `rejects a token signed with a different key`() {
        val issued = serviceAt(issuedAt, secret = SECRET).issueAccessToken(user)

        val otherService = serviceAt(issuedAt, secret = OTHER_SECRET)

        otherService.verify(issued.token).shouldBeNull()
    }

    @Test
    fun `rejects a token whose payload has been altered`() {
        val issued = serviceAt(issuedAt).issueAccessToken(user)
        val parts = issued.token.split(".")
        val forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"00000000-0000-0000-0000-000000000000","email":"attacker@example.com"}"""
                .toByteArray(),
        )

        val forged = "${parts[0]}.$forgedPayload.${parts[2]}"

        serviceAt(issuedAt).verify(forged).shouldBeNull()
    }

    @Test
    fun `rejects arbitrary junk without throwing`() {
        val service = serviceAt(issuedAt)

        service.verify("not-a-token").shouldBeNull()
        service.verify("").shouldBeNull()
        service.verify("a.b.c").shouldBeNull()
    }

    @Test
    fun `refuses to start with a key shorter than HS256 requires`() {
        val tooShort = Base64.getEncoder().encodeToString(ByteArray(31))

        val error =
            runCatching { serviceAt(issuedAt, secret = tooShort) }.exceptionOrNull()

        (error is IllegalArgumentException) shouldBe true
    }

    private fun propertiesWith(secret: String) = PlottedProperties(
        security = PlottedProperties.SecurityProperties(
            jwt = PlottedProperties.JwtProperties(secret = secret),
        ),
    )

    private companion object {
        val SECRET: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val OTHER_SECRET: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
    }
}
