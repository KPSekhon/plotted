package app.plotted.platform.ratelimit

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * What the limiter does when it cannot answer.
 *
 * The allow and refuse paths are arithmetic. The interesting behaviour is the
 * third one -- Redis unreachable -- because it is the case that decides whether a
 * dependency outage degrades the product or takes it down, and it is the case
 * nobody exercises until it happens in production.
 */
class RateLimitGuardTest {
    private val limiter = mockk<RateLimiter>()
    private val guard = RateLimitGuard(limiter)

    @Test
    fun `an allowed request passes through with its remaining allowance`() {
        every { limiter.tryAcquire(any(), any()) } returns RateLimiter.Decision.Allowed(remaining = 4)

        guard.check(RateLimits.PLAN, "user") shouldBe RateLimiter.Decision.Allowed(4)
    }

    @Test
    fun `a limited request is a 429 carrying how long to wait`() {
        every { limiter.tryAcquire(any(), any()) } returns
            RateLimiter.Decision.Limited(Duration.ofSeconds(42))

        val failure = shouldThrow<ApiException> { guard.check(RateLimits.PLAN, "user") }

        failure.code shouldBe ErrorCode.RATE_LIMITED
        // Without the retry hint a client either backs off by guessing or does
        // not back off at all, and the second is the common one.
        failure.errors["retryAfterSeconds"] shouldBe "42"
    }

    @Test
    fun `a fail-open limit lets the request through when the limiter is down`() {
        every { limiter.tryAcquire(any(), any()) } returns
            RateLimiter.Decision.Unavailable(allowed = true, reason = "connection refused")

        // The optimiser stays available. Being briefly unlimited costs CPU on an
        // authenticated endpoint; being unavailable costs the headline feature.
        guard.check(RateLimits.PLAN, "user") shouldBe
            RateLimiter.Decision.Unavailable(allowed = true, reason = "connection refused")
    }

    @Test
    fun `a fail-closed limit refuses when the limiter is down`() {
        every { limiter.tryAcquire(any(), any()) } returns
            RateLimiter.Decision.Unavailable(allowed = false, reason = "connection refused")

        // Demo account creation is unauthenticated and writes. A script can fill
        // a free-tier database through it, and a filled database does not recover
        // on its own the way a briefly unavailable demo does.
        val failure = shouldThrow<ApiException> { guard.check(RateLimits.DEMO_SESSION, "1.2.3.4") }

        // Reported as rate limited rather than as an internal error: from the
        // caller's side the fact and the remedy are the same, and "internal
        // error" invites an immediate retry.
        failure.code shouldBe ErrorCode.RATE_LIMITED
    }

    @Test
    fun `the two policies are assigned the way round they should be`() {
        // The whole point of declaring limits in one object is that this list is
        // reviewable. Asserting it means a change of policy is a decision
        // somebody makes rather than a default somebody inherits.
        RateLimits.DEMO_SESSION.policy shouldBe RateLimiter.Policy.FAIL_CLOSED
        RateLimits.PLAN.policy shouldBe RateLimiter.Policy.FAIL_OPEN
    }

    @Test
    fun `a limit of zero permits is refused at construction`() {
        // Zero permits is a disabled endpoint wearing a rate limit's clothes, and
        // it would present as every request being refused with a retry hint that
        // never comes good.
        shouldThrow<IllegalArgumentException> {
            RateLimiter.Limit("broken", permits = 0, window = Duration.ofMinutes(1), policy = RateLimiter.Policy.FAIL_OPEN)
        }
        shouldThrow<IllegalArgumentException> {
            RateLimiter.Limit("broken", permits = 5, window = Duration.ZERO, policy = RateLimiter.Policy.FAIL_OPEN)
        }
    }
}
