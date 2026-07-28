package app.plotted.platform.ratelimit

import java.time.Clock
import java.time.Duration

/**
 * A blocking token bucket, used to stay inside third-party rate limits.
 *
 * Section 17 of the specification is specific about this: rate limiting, backoff
 * and a refresh budget belong in the workflow rather than in a comment. Refresh
 * jobs walk thousands of titles, and the fastest way to lose access to the data
 * the whole product depends on is to discover the quota by exhausting it.
 *
 * Tokens accrue continuously rather than in fixed windows, so a burst is
 * permitted up to [burst] and the long-run rate still converges on
 * [permitsPerSecond]. Fixed windows allow twice the intended rate across a
 * window boundary, which is exactly when a limiter is most likely to be tested.
 *
 * Thread-safe by a single monitor. Contention is not a concern here: callers are
 * about to make a network request that costs orders of magnitude more than the
 * lock.
 *
 * @param sleeper injected so tests can drive time forward instead of waiting.
 */
class TokenBucket(
    private val permitsPerSecond: Double,
    private val burst: Int,
    private val clock: Clock,
    private val sleeper: (Duration) -> Unit = { duration ->
        if (!duration.isNegative && !duration.isZero) {
            Thread.sleep(duration.toMillis(), (duration.toNanosPart() % 1_000_000))
        }
    },
) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive, was $permitsPerSecond" }
        require(burst >= 1) { "burst must be at least 1, was $burst" }
    }

    private var tokens: Double = burst.toDouble()
    private var lastRefillNanos: Long = nowNanos()

    /** Blocks until a permit is available. */
    @Synchronized
    fun acquire() {
        while (true) {
            refill()
            if (tokens >= 1.0) {
                tokens -= 1.0
                return
            }
            sleeper(waitFor(1.0 - tokens))
        }
    }

    /** Takes a permit if one is available right now. Never blocks. */
    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    /** Available permits, for metrics and tests. */
    @Synchronized
    fun availablePermits(): Double {
        refill()
        return tokens
    }

    private fun refill() {
        val now = nowNanos()
        val elapsedNanos = now - lastRefillNanos
        if (elapsedNanos <= 0) return
        lastRefillNanos = now
        tokens = (tokens + elapsedNanos / NANOS_PER_SECOND * permitsPerSecond).coerceAtMost(burst.toDouble())
    }

    private fun waitFor(deficit: Double): Duration = Duration.ofNanos(Math.ceil(deficit / permitsPerSecond * NANOS_PER_SECOND).toLong())

    /**
     * Nanosecond resolution from the injected clock. [Clock.millis] would round a
     * 20-per-second budget to nothing.
     */
    private fun nowNanos(): Long = clock.instant().let { it.epochSecond * 1_000_000_000L + it.nano }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
