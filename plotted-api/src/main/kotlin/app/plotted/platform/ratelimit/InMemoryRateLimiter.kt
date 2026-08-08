package app.plotted.platform.ratelimit

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A fixed-window counter in this process's memory.
 *
 * ### Why this exists
 *
 * [RedisRateLimiter] fails *closed* on the endpoints where the limit is
 * load-bearing, which is right in production and catastrophic anywhere Redis
 * does not exist: the demo endpoint answers 429 forever, because the limiter can
 * never answer at all.
 *
 * That was discovered the first time the application ran on a machine without
 * Docker. Every part of the reasoning behind fail-closed was sound and the
 * conclusion was still wrong, because "Redis is briefly unavailable" and "there
 * is no Redis in this deployment" are different situations that produced the
 * same code path. A demo deployment on a free tier has no Redis by design, and
 * that is precisely the deployment whose demo endpoint has to work.
 *
 * So when no Redis is configured, the limits still apply -- they are simply
 * counted here.
 *
 * ### What it gives up
 *
 * The counter is per process, so with more than one instance each gets its own
 * allowance and the effective limit multiplies by the instance count. For a
 * single-instance deployment, which is what any environment without Redis is,
 * that is exact. For anything larger, run Redis: the point of the shared counter
 * is that it is shared.
 *
 * It also forgets everything on restart, which on a scale-to-zero host means a
 * cold start resets every window. Worth knowing, and much better than refusing
 * every request.
 */
/*
 * Chosen by an explicit property rather than by `@ConditionalOnMissingBean`,
 * which Spring's own documentation warns is order-dependent outside
 * auto-configuration. Which limiter is in use is worth being able to read off
 * the configuration rather than infer from bean-definition ordering.
 *
 * `memory` is the default, so the application works out of the box in every
 * environment that has no Redis -- which is every environment except a
 * deliberately provisioned one.
 */
@Component
@ConditionalOnProperty(
    prefix = "plotted.ratelimit",
    name = ["backend"],
    havingValue = "memory",
    matchIfMissing = true,
)
class InMemoryRateLimiter(
    private val meters: MeterRegistry,
    private val clock: Clock,
) : RateLimiter {
    private val log = LoggerFactory.getLogger(javaClass)
    private val windows = ConcurrentHashMap<String, Window>()

    init {
        log.info(
            "No Redis configured: rate limits are counted in this process. " +
                "Limits still apply; they are per instance rather than shared.",
        )
    }

    override fun tryAcquire(limit: RateLimiter.Limit, key: String): RateLimiter.Decision {
        val now = Instant.now(clock)
        val windowKey = "${limit.name}:$key"

        // compute() so the read, the expiry check and the increment are one
        // atomic step. Doing them separately lets two threads both observe the
        // last remaining permit, which is the same leak the Lua script exists to
        // prevent in the Redis implementation.
        val window = windows.compute(windowKey) { _, existing ->
            if (existing == null || !now.isBefore(existing.expiresAt)) {
                Window(count = 1, expiresAt = now.plus(limit.window))
            } else {
                existing.copy(count = existing.count + 1)
            }
        }!!

        // Bounded so a long-running process with many distinct keys does not
        // accumulate them forever. Cheap because it only runs when the map is
        // already larger than any real workload needs.
        if (windows.size > MAXIMUM_TRACKED_KEYS) {
            windows.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        }

        meters.counter("plotted.ratelimit.checked", "limit", limit.name).increment()

        if (window.count > limit.permits) {
            meters.counter("plotted.ratelimit.limited", "limit", limit.name).increment()
            return RateLimiter.Decision.Limited(Duration.between(now, window.expiresAt))
        }

        return RateLimiter.Decision.Allowed(remaining = limit.permits - window.count)
    }

    private data class Window(val count: Int, val expiresAt: Instant)

    private companion object {
        /** Far more than any single instance sees, and a bound rather than a leak. */
        const val MAXIMUM_TRACKED_KEYS = 10_000
    }
}
