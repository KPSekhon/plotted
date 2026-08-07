package app.plotted.platform.outbox

import app.plotted.platform.persistence.OutboxRelayRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The relay loop and the dispatch, without a database.
 *
 * The SQL is covered against real Postgres in [OutboxRelayIntegrationTest]. What
 * is left here is the part that decides what happens to an event, and the two
 * behaviours worth pinning are that one bad event does not take the batch with
 * it and that an unhandled event is not quietly filed as delivered.
 */
class OutboxRelayTest {
    private val repository = mockk<OutboxRelayRepository>(relaxed = true)
    private val properties = OutboxRelayProperties()

    @Test
    fun `a delivered event is marked published`() {
        val handled = mutableListOf<OutboxRecord>()
        val dispatcher = dispatcherWith(handler("test.event") { handled += it })
        val record = record("test.event")

        dispatcher.deliver(record) shouldBe true

        handled.map { it.id } shouldBe listOf(record.id)
        verify { repository.markPublished(record.id) }
        verify(exactly = 0) { repository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `a handler that throws is recorded rather than published`() {
        val dispatcher = dispatcherWith(handler("test.event") { error("downstream is down") })
        val record = record("test.event")

        dispatcher.deliver(record) shouldBe false

        val message = slot<String>()
        verify { repository.markFailed(record.id, capture(message), any()) }
        // Type as well as message, because a bare NullPointerException has a null
        // one and "null" in the column tells nobody anything.
        message.captured shouldBe "IllegalStateException: downstream is down"
        verify(exactly = 0) { repository.markPublished(any()) }
    }

    @Test
    fun `an event with no handler is not published`() {
        val dispatcher = dispatcherWith(handler("test.other") { })
        val record = record("test.event")

        dispatcher.deliver(record) shouldBe false

        // Publishing it would file the gap as a success: the row would be gone
        // and the work would never have happened.
        verify(exactly = 0) { repository.markPublished(any()) }
        verify { repository.markFailed(record.id, any(), any()) }
    }

    @Test
    fun `one bad event does not stop the ones behind it`() {
        val delivered = mutableListOf<Long>()
        val dispatcher = dispatcherWith(
            handler("good") { delivered += it.id },
            handler("bad") { error("nope") },
        )
        val relay = OutboxRelay(repository, dispatcher, properties)

        every { repository.claim(any(), any()) } returnsMany listOf(
            listOf(record("good", id = 1), record("bad", id = 2), record("good", id = 3)),
            emptyList(),
        )

        val report = relay.drain()

        // The whole reason each event is settled in its own transaction. A batch
        // in one transaction would roll the good deliveries back with the bad
        // one, then redeliver all of them next tick, forever.
        delivered shouldBe listOf(1L, 3L)
        report.delivered shouldBe 2
        report.failed shouldBe 1
    }

    @Test
    fun `draining stops when there is nothing left rather than spinning`() {
        val dispatcher = dispatcherWith(handler("test.event") { })
        val relay = OutboxRelay(repository, dispatcher, properties)
        every { repository.claim(any(), any()) } returns emptyList()

        relay.drain().delivered shouldBe 0

        // One empty claim per allowed batch and no more. The bound is what keeps
        // a busy queue from holding the scheduler thread indefinitely.
        verify(atMost = properties.maxBatchesPerPoll) { repository.claim(any(), any()) }
    }

    @Test
    fun `backoff grows and then stops growing`() {
        val dispatcher = dispatcherWith(handler("test.event") { })

        dispatcher.backoffFor(1) shouldBe Duration.ofSeconds(10)
        dispatcher.backoffFor(2) shouldBe Duration.ofSeconds(20)
        dispatcher.backoffFor(3) shouldBe Duration.ofSeconds(40)
        // Capped, because unbounded doubling reaches an interval measured in
        // weeks, which behaves like giving up without saying so.
        dispatcher.backoffFor(20) shouldBe properties.maximumBackoff
    }

    @Test
    fun `two handlers for one event type is a startup failure`() {
        // Otherwise the event goes to whichever bean the classpath happened to
        // order first, and the other one silently never runs.
        val failure = shouldThrow<IllegalArgumentException> {
            dispatcherWith(handler("test.event") { }, handler("test.event") { })
        }
        failure.message!!.contains("test.event") shouldBe true
    }

    // --- helpers -----------------------------------------------------------

    private fun dispatcherWith(vararg handlers: OutboxHandler) = OutboxDispatcher(repository, properties, handlers.toList())

    private fun handler(type: String, action: (OutboxRecord) -> Unit) = object : OutboxHandler {
        override val eventType: String = type

        override fun handle(record: OutboxRecord) = action(record)
    }

    private fun record(eventType: String, id: Long = 1, attempts: Int = 0) = OutboxRecord(
        id = id,
        aggregateType = "test",
        aggregateId = UUID.randomUUID(),
        eventType = eventType,
        payload = mapOf("k" to "v"),
        createdAt = Instant.EPOCH,
        attempts = attempts,
    )
}
