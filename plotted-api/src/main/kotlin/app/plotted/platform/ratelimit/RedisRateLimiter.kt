package app.plotted.platform.ratelimit

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * A fixed-window counter in Redis.
 *
 * ### Why a Lua script rather than INCR then EXPIRE
 *
 * The obvious implementation is `INCR`, and `EXPIRE` when the result is 1. It is
 * two round trips, and a process that dies between them leaves a key with no
 * expiry — a counter that never resets, so that caller is refused for as long as
 * the key survives. It happens rarely and never goes away by itself, which is the
 * worst combination.
 *
 * The script does both in one atomic step, so the key either exists with a TTL
 * or does not exist at all.
 *
 * ### Fixed window, and what that costs
 *
 * A caller can spend a full allowance at the end of one window and another at the
 * start of the next, so the true worst case over a short span is twice the
 * permits. A sliding window fixes that and costs a sorted set per caller plus a
 * trim on every request. For limits measured in tens per minute the doubling is
 * not worth that, and writing it down is better than implying a precision this
 * does not have.
 */
@Component
class RedisRateLimiter(
    private val redis: StringRedisTemplate,
    private val meters: MeterRegistry,
) : RateLimiter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun tryAcquire(limit: RateLimiter.Limit, key: String): RateLimiter.Decision {
        val redisKey = "ratelimit:${limit.name}:$key"

        val count = try {
            redis.execute(
                INCREMENT_AND_EXPIRE,
                listOf(redisKey),
                limit.window.seconds.toString(),
            )
        } catch (failure: Exception) {
            // Every Redis failure looks the same from here -- unreachable, timed
            // out, script error -- and the response to all of them is the same,
            // so they are caught together rather than enumerated into a list that
            // would go stale.
            return unavailable(limit, failure)
        } ?: return unavailable(limit, IllegalStateException("Rate limit script returned no value"))

        meters.counter("plotted.ratelimit.checked", "limit", limit.name).increment()

        if (count > limit.permits) {
            meters.counter("plotted.ratelimit.limited", "limit", limit.name).increment()
            val ttl = redis.getExpire(redisKey).takeIf { it > 0 } ?: limit.window.seconds
            return RateLimiter.Decision.Limited(Duration.ofSeconds(ttl))
        }

        return RateLimiter.Decision.Allowed(remaining = (limit.permits - count).toInt())
    }

    /**
     * What to do when Redis could not answer.
     *
     * Counted with the policy as a tag, because "the limiter has been failing
     * open for a week" is the thing worth alerting on and it is invisible if the
     * two policies share a counter. Logged at warn rather than error: it is a
     * degraded state the system is designed to survive, and an error log for
     * something with a defined response trains people to skip the log.
     */
    private fun unavailable(limit: RateLimiter.Limit, failure: Exception): RateLimiter.Decision {
        meters.counter(
            "plotted.ratelimit.unavailable",
            "limit",
            limit.name,
            "policy",
            limit.policy.name.lowercase(),
        ).increment()

        log.warn(
            "Rate limiter unavailable for '{}', failing {}: {}",
            limit.name,
            if (limit.policy == RateLimiter.Policy.FAIL_OPEN) "open" else "closed",
            failure.message,
        )

        return RateLimiter.Decision.Unavailable(
            allowed = limit.policy == RateLimiter.Policy.FAIL_OPEN,
            reason = failure.message ?: failure::class.java.simpleName,
        )
    }

    private companion object {
        /**
         * Increment, and set the expiry only on the first hit of a window.
         *
         * Re-setting the TTL on every request would turn a fixed window into a
         * sliding one that never rolls while traffic continues -- a caller making
         * one request a second would be refused forever after their first burst,
         * because the key would never be allowed to expire.
         */
        val INCREMENT_AND_EXPIRE: DefaultRedisScript<Long> = DefaultRedisScript(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """.trimIndent(),
            Long::class.java,
        )
    }
}
