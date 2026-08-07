package app.plotted.platform.outbox

import app.plotted.generated.jooq.tables.references.OUTBOX
import app.plotted.platform.persistence.OutboxRelayRepository
import app.plotted.platform.persistence.OutboxRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

/**
 * The outbox relay against a real database.
 *
 * Almost everything here depends on behaviour Postgres provides and nothing else
 * checks: `UPDATE ... RETURNING` with `FOR UPDATE SKIP LOCKED` in a subquery, and
 * the lease that keeps a second worker away from a claimed row. A mock would
 * happily agree with any of it.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class OutboxRelayIntegrationTest {
    @Autowired
    private lateinit var writer: OutboxRepository

    @Autowired
    private lateinit var relay: OutboxRelayRepository

    @Autowired
    private lateinit var dsl: DSLContext

    /**
     * An empty outbox before each test.
     *
     * The container is shared by every test in this class, and three of the
     * assertions here are about the table as a whole rather than about one row —
     * ordering, and the pending and stuck counts, which are global by design
     * because they exist to be reported as metrics. Scoping those assertions to
     * ids would be testing something weaker than the thing that ships. Clearing
     * the table is the honest way to make them mean what they say.
     */
    @BeforeEach
    fun emptyTheOutbox() {
        dsl.deleteFrom(OUTBOX).execute()
    }

    @Test
    fun `a claimed event is not claimed again while its lease holds`() {
        val id = givenEvent("test.one")

        val first = relay.claim(10, Duration.ofMinutes(2))
        first.map { it.id } shouldBe listOf(id)

        // The whole reason the claim is an UPDATE rather than a bare SELECT FOR
        // UPDATE. The row lock from the first claim is long gone -- that
        // transaction committed -- so if the lease were not written, a second
        // worker would pick the same event straight back up and deliver it twice
        // on every tick.
        relay.claim(10, Duration.ofMinutes(2)).shouldBeEmpty()
    }

    @Test
    fun `an expired lease makes the event claimable again`() {
        val id = givenEvent("test.two")

        // A zero lease stands in for a worker that died mid-batch. Its events
        // must come back rather than being stranded, which is the other half of
        // the at-least-once contract.
        relay.claim(10, Duration.ZERO).map { it.id } shouldBe listOf(id)
        relay.claim(10, Duration.ofMinutes(2)).map { it.id } shouldBe listOf(id)
    }

    @Test
    fun `events are claimed in the order they were written`() {
        val first = givenEvent("test.order")
        val second = givenEvent("test.order")
        val third = givenEvent("test.order")

        relay.claim(10, Duration.ofMinutes(2)).map { it.id } shouldBe listOf(first, second, third)
    }

    @Test
    fun `the batch size bounds a claim`() {
        repeat(5) { givenEvent("test.batch") }

        relay.claim(2, Duration.ofMinutes(2)).size shouldBe 2
    }

    @Test
    fun `publishing settles an event for good`() {
        val id = givenEvent("test.publish")
        relay.claim(10, Duration.ofMinutes(2))

        relay.markPublished(id)

        publishedAt(id).shouldNotBeNull()
        attempts(id) shouldBe 1
        // published_at is the only thing that takes it out of circulation. If the
        // lease alone were relied on, every delivered event would come back the
        // moment it expired.
        relay.claim(10, Duration.ZERO).shouldBeEmpty()
    }

    @Test
    fun `a failure records why and schedules a retry`() {
        val id = givenEvent("test.fail")
        relay.claim(10, Duration.ofMinutes(2))

        relay.markFailed(id, "IllegalStateException: downstream is down", Duration.ofMinutes(30))

        attempts(id) shouldBe 1
        lastError(id) shouldBe "IllegalStateException: downstream is down"
        publishedAt(id).shouldBeNull()
        // Backed off past the lease, so it is not simply picked up next tick.
        relay.claim(10, Duration.ofMinutes(2)).shouldBeEmpty()
    }

    @Test
    fun `a long error is truncated rather than stored whole`() {
        val id = givenEvent("test.longerror")

        relay.markFailed(id, "x".repeat(5_000), Duration.ofMinutes(1))

        // A stack trace in a column is how one poisoned event produces a table
        // nobody can read. The useful part is always at the front.
        lastError(id)!!.length shouldBe 1_000
    }

    @Test
    fun `stuck events are counted rather than hidden`() {
        val id = givenEvent("test.stuck")
        repeat(6) { relay.markFailed(id, "still down", Duration.ZERO) }

        relay.stuckCount(6) shouldBe 1
        // Still pending, and still retried at the capped interval. Parking it
        // would need a person to notice and requeue, and the usual cause is an
        // outage that ends on its own.
        relay.pendingCount() shouldBe 1
        relay.claim(10, Duration.ofMinutes(2)).map { it.id } shouldBe listOf(id)
    }

    @Test
    fun `the payload survives the round trip`() {
        val aggregateId = UUID.randomUUID()
        writer.append(
            OutboxRepository.OutboxEvent(
                aggregateType = "title",
                aggregateId = aggregateId,
                eventType = "test.payload",
                payload = mapOf("titleId" to aggregateId.toString(), "confidence" to 0.8, "regionCode" to "CA"),
            ),
        )

        val claimed = relay.claim(10, Duration.ofMinutes(2)).single { it.eventType == "test.payload" }

        claimed.aggregateId shouldBe aggregateId
        claimed.payload["regionCode"] shouldBe "CA"
        // A number that came back as a String would break the handler's cast, and
        // it would break it at delivery time rather than here.
        (claimed.payload["confidence"] as Number).toDouble() shouldBe 0.8
    }

    // --- helpers -----------------------------------------------------------

    private fun givenEvent(eventType: String): Long = writer.append(
        OutboxRepository.OutboxEvent(
            aggregateType = "test",
            aggregateId = UUID.randomUUID(),
            eventType = eventType,
            payload = mapOf("k" to "v"),
        ),
    )

    private fun attempts(id: Long) = dsl.select(OUTBOX.ATTEMPTS).from(OUTBOX).where(OUTBOX.ID.eq(id)).fetchOne()!!.value1()

    private fun lastError(id: Long) = dsl.select(OUTBOX.LAST_ERROR).from(OUTBOX).where(OUTBOX.ID.eq(id)).fetchOne()!!.value1()

    private fun publishedAt(id: Long) = dsl.select(OUTBOX.PUBLISHED_AT).from(OUTBOX).where(OUTBOX.ID.eq(id)).fetchOne()!!.value1()

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("plotted")
                .withUsername("plotted")
                .withPassword("plotted")
    }
}
