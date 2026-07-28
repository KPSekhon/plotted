package app.plotted.platform.ratelimit

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Time is driven by the test rather than waited on: the sleeper advances the
 * clock by exactly the duration it was asked to sleep for. That makes the
 * behaviour that matters -- how long a caller is actually made to wait --
 * directly assertable, and keeps the suite instant.
 */
class TokenBucketTest {
    private val start = Instant.parse("2026-07-27T12:00:00Z")

    private class MutableClock(
        var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private fun bucketOf(permitsPerSecond: Double, burst: Int): Triple<TokenBucket, MutableClock, MutableList<Duration>> {
        val clock = MutableClock(start)
        val slept = mutableListOf<Duration>()
        val bucket = TokenBucket(permitsPerSecond, burst, clock) { duration ->
            slept += duration
            clock.instant = clock.instant.plus(duration)
        }
        return Triple(bucket, clock, slept)
    }

    @Test
    fun `a full bucket lets the whole burst through without waiting`() {
        val (bucket, _, slept) = bucketOf(permitsPerSecond = 10.0, burst = 5)

        repeat(5) { bucket.acquire() }

        slept.shouldBe(emptyList())
    }

    @Test
    fun `the call after the burst waits for a token to accrue`() {
        val (bucket, _, slept) = bucketOf(permitsPerSecond = 10.0, burst = 5)
        repeat(5) { bucket.acquire() }

        bucket.acquire()

        slept.size shouldBe 1
        // Ten per second means one token every hundred milliseconds.
        slept.single() shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `the long-run rate converges on the configured rate`() {
        val (bucket, clock, _) = bucketOf(permitsPerSecond = 20.0, burst = 20)

        repeat(100) { bucket.acquire() }

        // 20 free from the burst, 80 paid for at 20/second, so four seconds.
        val elapsed = Duration.between(start, clock.instant)
        elapsed shouldBe Duration.ofSeconds(4)
    }

    @Test
    fun `tokens accrue while the caller is idle, up to the burst ceiling`() {
        val (bucket, clock, _) = bucketOf(permitsPerSecond = 10.0, burst = 5)
        repeat(5) { bucket.acquire() }

        // Idle far longer than it takes to refill.
        clock.instant = clock.instant.plus(Duration.ofMinutes(1))

        // Refills to the ceiling, not beyond it: no saved-up burst of 600.
        bucket.availablePermits() shouldBe 5.0
    }

    @Test
    fun `tryAcquire never blocks and reports exhaustion truthfully`() {
        val (bucket, clock, slept) = bucketOf(permitsPerSecond = 10.0, burst = 2)

        bucket.tryAcquire() shouldBe true
        bucket.tryAcquire() shouldBe true
        bucket.tryAcquire() shouldBe false
        slept.shouldBe(emptyList())

        clock.instant = clock.instant.plus(Duration.ofMillis(100))

        bucket.tryAcquire() shouldBe true
    }

    @Test
    fun `partial tokens accumulate rather than being rounded away`() {
        val (bucket, clock, _) = bucketOf(permitsPerSecond = 10.0, burst = 5)
        repeat(5) { bucket.acquire() }

        // Half of what a token costs. A millisecond-resolution implementation
        // would lose this entirely at higher rates.
        clock.instant = clock.instant.plus(Duration.ofMillis(50))

        val available = bucket.availablePermits()
        available shouldBeGreaterThan 0.4
        available shouldBeLessThanOrEqual 0.6
    }

    @Test
    fun `rejects a configuration that could never issue a permit`() {
        val clock = MutableClock(start)

        runCatching { TokenBucket(0.0, 1, clock) }.isFailure shouldBe true
        runCatching { TokenBucket(10.0, 0, clock) }.isFailure shouldBe true
    }
}
