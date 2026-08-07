package app.plotted.platform.ratelimit

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import org.springframework.stereotype.Component

/**
 * Applies a limit and turns a refusal into the right HTTP answer.
 *
 * A thin thing on purpose. It exists so that a controller says
 * `guard.check(RateLimits.PLAN, key)` rather than repeating the `when` over
 * [RateLimiter.Decision] at every call site — and so that the `Retry-After`
 * header cannot be forgotten at one of them, which is the difference between a
 * client that backs off and a client that hammers.
 */
@Component
class RateLimitGuard(
    private val limiter: RateLimiter,
) {
    /**
     * Consumes one permit, or throws.
     *
     * Returns the decision on the way through, so a caller that wants to report
     * the remaining allowance can, without a second check that would consume
     * another permit.
     */
    fun check(limit: RateLimiter.Limit, key: String): RateLimiter.Decision {
        val decision = limiter.tryAcquire(limit, key)

        when (decision) {
            is RateLimiter.Decision.Allowed -> Unit

            is RateLimiter.Decision.Limited -> throw ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many requests. Try again in ${decision.retryAfter.seconds} seconds.",
                mapOf("retryAfterSeconds" to decision.retryAfter.seconds.toString()),
            )

            is RateLimiter.Decision.Unavailable -> if (!decision.allowed) {
                // A fail-closed limit whose limiter is down. Reported as rate
                // limited rather than as an internal error, because from the
                // caller's side the fact and the remedy are the same: too many
                // requests are getting through, wait and retry. Saying "internal
                // error" would invite an immediate retry.
                throw ApiException(
                    ErrorCode.RATE_LIMITED,
                    "This endpoint is temporarily unavailable. Try again shortly.",
                    mapOf("reason" to "rate limiter unavailable"),
                )
            }
        }

        return decision
    }
}
